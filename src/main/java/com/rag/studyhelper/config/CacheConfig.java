package com.rag.studyhelper.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存配置。<b>本项目缓存的三个真实场景</b>：
 *
 * <ol>
 *   <li><b>documents</b>：文档列表。读多写少，但每次都要查 MySQL 全表排序。
 *       失效时机明确（入库成功 / 删除成功），是最标准的缓存场景。</li>
 *   <li><b>embedding</b>：文本 → 向量。Embedding 是<b>幂等</b>的（同样的文字永远得到同样的向量），
 *       而且是收费 API。缓存它能直接省钱、省时间。</li>
 *   <li><b>retrieval</b>：问题 → 检索到的文档片段。相同问题反复问是真实高频场景
 *       （比如面试前反复确认某个知识点）。这里存的是<b>检索结果</b>而不是最终答案，
 *       因为答案依赖对话历史，缓存答案会导致多轮对话串味。</li>
 * </ol>
 *
 * <p><b>为什么每个缓存区（cache name）配置不同的 TTL？</b>
 * <ul>
 *   <li>文档列表：变化由我们自己控制，TTL 可以长（10 分钟），主要靠主动失效。</li>
 *   <li>Embedding：向量是纯函数结果，理论上永不过期。给 24 小时是为了防止
 *       "换了 Embedding 模型但忘了清缓存"导致维度不匹配——留一个自愈的口子。</li>
 *   <li>检索结果：知识库随时可能新增文档，TTL 必须短（5 分钟），
 *       否则新传的文档要等 5 分钟才能被搜到，用户会以为上传失败了。</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig extends CachingConfigurerSupport {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    public static final String CACHE_DOCUMENTS = "documents";
    public static final String CACHE_EMBEDDING = "embedding";
    public static final String CACHE_RETRIEVAL = "retrieval";

    @Value("${app.cache.documents-ttl-minutes:10}")
    private long documentsTtlMinutes;

    @Value("${app.cache.embedding-ttl-hours:24}")
    private long embeddingTtlHours;

    @Value("${app.cache.retrieval-ttl-minutes:5}")
    private long retrievalTtlMinutes;

    @Value("${app.cache.key-prefix:rag:cache:}")
    private String keyPrefix;

    /**
     * 缓存专用序列化器。
     * <p>
     * 用 JSON 而不是 JDK 序列化，原因有三：
     * <ol>
     *   <li>JDK 序列化要求类实现 {@code Serializable}，且类结构一改（加字段）就反序列化失败；</li>
     *   <li>JSON 在 redis-cli 里能直接看懂，排查问题时不用写 Java 代码；</li>
     *   <li>跨语言兼容，将来别的服务也能读同一份缓存。</li>
     * </ol>
     * <p>
     * 开启 {@code activateDefaultTyping} 是为了在 JSON 里保留类型信息——
     * 否则 {@code List<DocumentInfo>} 取出来会变成 {@code List<LinkedHashMap>}，
     * 调用方一强转就 {@code ClassCastException}。这是 Redis JSON 缓存最经典的坑。
     */
    private RedisCacheConfiguration baseConfig(Duration ttl) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 只允许反序列化到白名单里的包，避免 Jackson 多态反序列化带来的安全漏洞
        // （攻击者可以在 JSON 里塞任意类名触发 gadget 链）。
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.rag.studyhelper.")
                        .allowIfSubType("dev.langchain4j.data.segment.")
                        .allowIfSubType("dev.langchain4j.data.document.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .prefixCacheNameWith(keyPrefix)
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer(mapper)))
                // 不缓存 null，配合下面的空值防护一起用
                .disableCachingNullValues();
    }

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> configs = new HashMap<>();
        configs.put(CACHE_DOCUMENTS, baseConfig(Duration.ofMinutes(documentsTtlMinutes)));
        configs.put(CACHE_EMBEDDING, baseConfig(Duration.ofHours(embeddingTtlHours)));
        configs.put(CACHE_RETRIEVAL, baseConfig(Duration.ofMinutes(retrievalTtlMinutes)));

        log.info("CacheManager 初始化: documents={}min, embedding={}h, retrieval={}min, prefix={}",
                documentsTtlMinutes, embeddingTtlHours, retrievalTtlMinutes, keyPrefix);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig(Duration.ofMinutes(10)))
                .withInitialCacheConfigurations(configs)
                .build();
    }

    /**
     * 缓存读写异常时<b>不抛出</b>，只记日志。
     * <p>
     * <b>这是有意为之的降级设计</b>：Redis 挂了不应该让整个业务挂掉。
     * 缓存是"加速器"，不是"数据源"——拿不到缓存就回源查库，
     * 用户感知到的只是变慢，而不是报错。
     * <p>
     * 反例：很多项目 Redis 一抖动，全站 500，就是因为没有处理这类异常。
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception,
                                            org.springframework.cache.Cache cache, Object key) {
                log.warn("缓存读取失败，回源处理: cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception,
                                            org.springframework.cache.Cache cache, Object key, Object value) {
                log.warn("缓存写入失败，忽略: cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception,
                                              org.springframework.cache.Cache cache, Object key) {
                log.warn("缓存失效失败: cache={}, key={}, err={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception,
                                              org.springframework.cache.Cache cache) {
                log.warn("缓存清空失败: cache={}, err={}", cache.getName(), exception.getMessage());
            }
        };
    }
}
