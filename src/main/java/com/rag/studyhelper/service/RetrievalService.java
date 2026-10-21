package com.rag.studyhelper.service;

import com.rag.studyhelper.config.CacheConfig;
import com.rag.studyhelper.config.CacheKeyGenerator;
import com.rag.studyhelper.model.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索服务：向量召回 → 阈值过滤 → Rerank 重排，带结果缓存。
 * <p>
 * <b>为什么单独抽一个类？（这是本次改造最关键的一处修正）</b>
 * <p>
 * 最初我把 {@code retrieve()} 直接写在 {@link RagQueryService} 里，
 * 结果 {@code @Cacheable} <b>完全不生效</b>——日志显示每次都"缓存未命中"，
 * Redis 里一个 key 都没有，但也不报错。
 * <p>
 * <b>根因</b>：Spring Cache 依赖 AOP 代理。当 {@code RagQueryService} 内部
 * 的 {@code streamAnswer()} 直接调用自己的 {@code retrieve()} 时，
 * 这次调用<b>不经过代理对象</b>，而是走 {@code this} 引用，
 * 所以 {@code @Cacheable} 注解被完全跳过。
 * <p>
 * 这叫 <b>self-invocation（自调用）问题</b>，是 Spring AOP 最经典的坑：
 * <pre>
 *   ❌ 无效：a() { this.b(); }        // b() 上的注解不生效
 *   ✅ 有效：a() { otherBean.b(); }   // 跨 Bean 调用，走代理
 * </pre>
 * <b>三种解法</b>：
 * <ol>
 *   <li>把方法拆到另一个 Bean（本类的做法，最干净）；</li>
 *   <li>注入自身代理 {@code @Lazy RagQueryService self}，再 {@code self.retrieve()}；</li>
 *   <li>用 {@code AopContext.currentProxy()}（需开 {@code exposeProxy=true}，侵入性强）。</li>
 * </ol>
 * 选第一种：职责也更清晰——"检索"和"编排"本来就是两件事。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RerankService rerankService;
    private final CacheManager cacheManager;

    @Value("${app.rag.score-threshold:0.77}")
    private double scoreThreshold;

    /** 向量召回条数 */
    @Value("${app.rag.recall-top-k:20}")
    private int recallTopK;

    /** Rerank 后最终保留条数 */
    @Value("${app.rag.rerank-top-n:5}")
    private int rerankTopN;

    public RetrievalService(EmbeddingStore<TextSegment> embeddingStore,
                           RerankService rerankService,
                           CacheManager cacheManager) {
        this.embeddingStore = embeddingStore;
        this.rerankService = rerankService;
        this.cacheManager = cacheManager;
    }

    /**
     * 带缓存的检索。
     * <p>
     * <b>缓存 key 只用一个参数</b>（{@code #searchQuery}），不包含 {@code questionEmbedding}。
     * 这是刻意的：向量是文本的确定性函数，同一个 query 的向量必然相同，
     * 把它塞进 key 只会让 key 变长、可读性变差，没有任何额外区分度。
     * <p>
     * <b>缓存 value 是自定义 DTO 列表</b>，不是 LangChain4j 的 {@code TextSegment}——
     * 原因见 {@link RetrievedChunk} 的注释（第三方类可能无法反序列化）。
     * <p>
     * <b>{@code unless = "#result == null || #result.isEmpty()"} 是关键</b>，
     * 它表示"结果为空时不写缓存"。为什么必须加？见 {@link #evictEmptyResult} 的说明。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RETRIEVAL,
            key = "T(com.rag.studyhelper.config.CacheKeyGenerator).sha256(#searchQuery)",
            unless = "#result == null || #result.isEmpty()")
    public List<RetrievedChunk> retrieve(String searchQuery, Embedding questionEmbedding) {
        log.info("检索缓存未命中，执行向量检索: query={}", searchQuery);

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(recallTopK)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        for (EmbeddingMatch<TextSegment> m : matches) {
            log.info("  score={} content={}", m.score(),
                    m.embedded().text().substring(0, Math.min(80, m.embedded().text().length())));
        }

        if (!matches.isEmpty()) {
            DoubleSummaryStatistics stats = matches.stream()
                    .mapToDouble(EmbeddingMatch::score).summaryStatistics();
            long above90 = matches.stream().filter(m -> m.score() >= 0.90).count();
            long above80 = matches.stream().filter(m -> m.score() >= 0.80 && m.score() < 0.90).count();
            long above70 = matches.stream().filter(m -> m.score() >= 0.70 && m.score() < 0.80).count();
            long below70 = matches.stream().filter(m -> m.score() < 0.70).count();
            log.info("Score distribution: max={} min={} avg={} | ≥0.90={} 0.80-0.89={} 0.70-0.79={} <0.70={}",
                    stats.getMax(), stats.getMin(), stats.getAverage(),
                    above90, above80, above70, below70);
        }

        // 阈值过滤：相似度太低的内容喂给 LLM 只会误导它
        List<EmbeddingMatch<TextSegment>> filtered = matches.stream()
                .filter(m -> m.score() >= scoreThreshold)
                .collect(Collectors.toList());
        log.info("After filtering query={} score>={} chunks={}", searchQuery, scoreThreshold, filtered.size());

        List<RetrievedChunk> result = new ArrayList<>();
        if (!filtered.isEmpty()) {
            List<TextSegment> segments = filtered.stream()
                    .map(EmbeddingMatch::embedded)
                    .collect(Collectors.toList());
            List<TextSegment> reranked = rerankService.rerank(searchQuery, segments, rerankTopN);
            for (TextSegment seg : reranked) {
                result.add(new RetrievedChunk(seg.text(),
                        RetrievedChunk.parseFileName(seg.text()), -1));
            }
        }

        // 空结果的处理在这里不能"清除缓存"，原因见 evictEmptyResult 注释。
        // 真正的拦截点是方法上 @Cacheable 的 unless 条件。
        if (result.isEmpty()) {
            log.info("检索结果为空（该 query 不会被写入缓存）: query={}", searchQuery);
        }

        log.info("检索完成: query={}, 最终片段数={}", searchQuery, result.size());
        return result;
    }

    /**
     * 为什么空结果<b>不能</b>在方法内部清除缓存？（踩过的坑，留作记录）
     * <p>
     * 我最初在 {@code retrieve()} 末尾写了"如果结果为空就 {@code cache.evict(key)}"，
     * 日志确实打印了"已清除"，但 Redis 里那个 key 依然存在，值是 {@code []}。
     * <p>
     * <b>原因在于执行顺序</b>：
     * <pre>
     *   拦截器 before → 方法体执行（此处 evict，缓存此刻还是空的，等于做了无用功）
     *                  → 方法返回
     *   拦截器 after  → 把返回值 [] 写入缓存   ← 清除被这一步覆盖
     * </pre>
     * 也就是说，<b>方法体内的清除永远发生在缓存写入之前</b>，注定无效。
     * <p>
     * <b>正确做法</b>是让拦截器自己跳过写入，即
     * {@code @Cacheable(unless = "#result.isEmpty()")}。
     * <p>
     * <b>那为什么还留着这个方法？</b>它不是给 {@code retrieve()} 用的，
     * 而是给"需要按动态 key 主动清某个 query"的场景预留的（例如知识库变更后
     * 想精准清掉某几条 query 的缓存），此时调用发生在方法之外，不受上述顺序影响。
     * <p>
     * 通用教训：<b>凡是"根据返回值决定是否缓存"，都应该用 unless，
     * 而不是在方法体里手动 evict。</b>
     */
    public void evictEmptyResult(String searchQuery) {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_RETRIEVAL);
        if (cache != null) {
            cache.evict(CacheKeyGenerator.sha256(searchQuery));
        }
    }
}
