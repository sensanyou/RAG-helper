package com.rag.studyhelper.service;

import com.rag.studyhelper.config.CacheConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 带缓存的 Embedding 服务。
 * <p>
 * <b>为什么值得单独做一层？</b>
 * Embedding 调用有三个特征，让它成为最理想的缓存对象：
 * <ol>
 *   <li><b>幂等</b>：同样的文本永远得到同样的向量，缓存不存在一致性问题。</li>
 *   <li><b>贵</b>：按 token 收费，一次问答至少 1 次调用，批量入库则是 N/10 次。</li>
 *   <li><b>慢</b>：单次 100ms~1s，是问答链路里仅次于 LLM 的耗时大户。</li>
 * </ol>
 * 这三条合起来就是"缓存收益最高、风险最低"的教科书案例。
 * <p>
 * <b>⚠ 一个关键的实现约束（踩过的坑）</b>：
 * 不能直接把 {@link Embedding} 对象塞进 Redis 缓存。
 * 因为它是第三方库的类，<b>没有无参构造函数</b>，Jackson 反序列化时会报
 * <pre>
 * Cannot construct instance of `dev.langchain4j.data.embedding.Embedding`
 * (no Creators, like default constructor, exist)
 * </pre>
 * 所以这里缓存的是 <b>{@code float[]} 向量数组</b>——它是 JDK 原生类型，
 * JSON 序列化/反序列化毫无障碍，取出来再 {@code Embedding.from(...)} 拼回去。
 * <p>
 * <b>这个坑的通用教训</b>：缓存里放的应该是"数据"，不是"对象"。
 * 凡是跨序列化边界的，优先用 DTO / 基础类型 / 数组，不要用第三方值对象。
 */
@Service
public class CachedEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(CachedEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    /**
     * 注入"自己的代理对象"。
     * <p>
     * <b>为什么需要这个奇怪的东西？</b>
     * 因为 {@link #embed(String)} 内部要调用 {@link #embedVector(String)}，
     * 而这属于<b>同类自调用（self-invocation）</b>——走的是 {@code this} 引用，
     * 不经过 Spring 的 AOP 代理，导致 {@code @Cacheable} 被完全跳过。
     * <p>
     * 表现是：缓存永远不命中，Redis 里连一个 key 都没有，但也不报任何错。
     * （和 {@link RetrievalService} 注释里记录的是同一个坑的第二次踩到。）
     * <p>
     * 这里用 {@code @Lazy} 注入自身代理，是为了打破循环依赖：
     * 这个 Bean 在初始化时需要拿到"已经代理好的自己"，
     * 直接注入会形成"自己依赖自己"的循环，加 {@code @Lazy} 后 Spring 注入的是一个
     * 延迟解析的代理引用，真正调用时才去容器里取。
     */
    @Lazy
    @Autowired
    private CachedEmbeddingService selfProxy;

    public CachedEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 单条文本转向量（带缓存）。
     * <p>
     * 缓存的是 {@code float[]}，不是 {@code Embedding}，原因见类注释。
     * key 用文本的 SHA-256，保证定长且不泄露原文。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_EMBEDDING,
            key = "T(com.rag.studyhelper.config.CacheKeyGenerator).sha256(#text)")
    public float[] embedVector(String text) {
        log.debug("Embedding 缓存未命中，调用远程 API: len={}", text.length());
        return embeddingModel.embed(text).content().vector();
    }

    /**
     * 单条文本转向量，返回 LangChain4j 的 {@link Embedding} 对象。
     * <p>
     * 这个方法<b>不加缓存注解</b>——它只是把 {@link #embedVector(String)} 的结果包一层。
     * 如果这里也加注解，就会变成"缓存里套缓存"，浪费一层序列化开销。
     * <p>
     * <b>注意这里调用的是 {@code selfProxy.embedVector()} 而不是 {@code this.embedVector()}。</b>
     * 这是本方法唯一"不自然"的地方，但必须如此：只有经过代理对象调用，
     * {@code @Cacheable} 才会被拦截执行。写 {@code embedVector(text)}（等价于 {@code this.}）
     * 会让缓存静默失效——这正是之前 Embedding 缓存一直不生效的原因。
     */
    public Embedding embed(String text) {
        return Embedding.from(selfProxy.embedVector(text));
    }

    /**
     * 批量文本转向量。
     * <p>
     * <b>逐条走缓存</b>，而不是整批查一次。
     * 因为批量的 key 需要对整个列表求摘要，列表里任何一项变化都会让整批失效，
     * 命中率反而极低。逐条缓存后，重复的分块（表头、模板段落）能单独命中。
     */
    public List<Embedding> embedAll(List<String> texts) {
        List<Embedding> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(Embedding.from(selfProxy.embedVector(text)));
        }
        return result;
    }
}
