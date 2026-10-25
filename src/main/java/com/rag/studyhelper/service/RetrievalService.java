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
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 检索服务：向量召回 → 阈值过滤 → Rerank 重排，带结果缓存。
 * <p>
 * <b>为什么单独抽一个类？（这是缓存改造最关键的一处修正）</b>
 * <p>
 * 最初把 {@code retrieve()} 直接写在 {@link RagQueryService} 里，
 * 结果 {@code @Cacheable} <b>完全不生效</b>——日志显示每次都"缓存未命中"，
 * Redis 里一个 key 都没有，但也不报错。
 * 根因是 Spring AOP 的 <b>self-invocation</b>：同类内部调用走 {@code this}，
 * 不经过代理，注解被静默跳过。拆成独立 Bean 后调用跨过代理边界，缓存才生效。
 * <p>
 * <b>V2 多用户改造（本类的安全核心）：</b>
 * <ol>
 *   <li><b>检索时过滤</b>：向量库里每个片段都带 {@code userId} metadata，
 *       检索请求直接带 filter——只召回"本人 + 系统文档"。
 *       <b>绝不能检索完再在 Java 里过滤</b>：top20 可能全是别人的，
 *       过滤完剩 0 条，召回率塌陷；更严重的是"检索完再过滤"意味着
 *       别人的内容已经进入了你的请求路径。</li>
 *   <li><b>缓存 key 必须带 userId</b>：key 若只有 query 哈希，
 *       用户 B 问同样的问题会直接命中用户 A 缓存的检索片段——
 *       这是跨用户数据泄露，且不报任何错。多用户系统里，
 *       <b>任何缓存 key 都要问一句"这里面的数据属于谁"</b>。</li>
 * </ol>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** 系统文档的 ownerId 约定值，与 documents 表的 owner_id=0 对应 */
    public static final String SYSTEM_USER = "0";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RerankService rerankService;

    @Value("${app.rag.score-threshold:0.77}")
    private double scoreThreshold;

    /** 向量召回条数 */
    @Value("${app.rag.recall-top-k:20}")
    private int recallTopK;

    /** Rerank 后最终保留条数 */
    @Value("${app.rag.rerank-top-n:5}")
    private int rerankTopN;

    public RetrievalService(EmbeddingStore<TextSegment> embeddingStore,
                           RerankService rerankService) {
        this.embeddingStore = embeddingStore;
        this.rerankService = rerankService;
    }

    /**
     * 当前用户可见的数据范围：本人文档 + 系统文档（ownerId=0）。
     * <p>
     * 入库时把 ownerId 写进了每个片段的 metadata（{@code userId} 键），
     * 所以检索过滤发生在向量库内部——这是"权限过滤在召回阶段生效"的关键。
     */
    public static Filter visibleTo(long userId) {
        return metadataKey("userId").isEqualTo(String.valueOf(userId))
                .or(metadataKey("userId").isEqualTo(SYSTEM_USER));
    }

    /**
     * 带缓存的检索。
     * <p>
     * <b>缓存 key = sha256(userId + query)</b>：同一个人重复问才命中；
     * 不同用户问同样的问题，各自检索、各自缓存。
     * <p>
     * <b>{@code unless} 空结果不写缓存</b>：方法体内 evict 无效（写入发生在返回后），
     * 这是踩过的坑——清除永远发生在拦截器写入之前，必须让拦截器自己跳过。
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_RETRIEVAL,
            key = "T(com.rag.studyhelper.config.CacheKeyGenerator).sha256('u' + #userId + '|' + #searchQuery)",
            unless = "#result == null || #result.isEmpty()")
    public List<RetrievedChunk> retrieve(long userId, String searchQuery, Embedding questionEmbedding) {
        log.info("检索缓存未命中，执行向量检索: userId={}, query={}", userId, searchQuery);

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(recallTopK)
                .filter(visibleTo(userId))
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        if (!matches.isEmpty()) {
            DoubleSummaryStatistics stats = matches.stream()
                    .mapToDouble(EmbeddingMatch::score).summaryStatistics();
            log.info("Score distribution: max={} min={} avg={} count={}",
                    stats.getMax(), stats.getMin(), stats.getAverage(), matches.size());
        }

        // 阈值过滤：相似度太低的内容喂给 LLM 只会误导它
        List<EmbeddingMatch<TextSegment>> filtered = matches.stream()
                .filter(m -> m.score() >= scoreThreshold)
                .collect(Collectors.toList());
        log.info("After filtering query={} score>={} chunks={}", searchQuery, scoreThreshold, filtered.size());

        List<RetrievedChunk> result = new ArrayList<>();
        if (!filtered.isEmpty()) {
            // Rerank 只返回重排后的 Segment，向量分数会丢；
            // 先建 text→score 映射，重排后按正文找回，让前端能展示"依据强度"
            Map<String, Double> scoreByText = new HashMap<>();
            for (EmbeddingMatch<TextSegment> m : filtered) {
                scoreByText.put(m.embedded().text(), m.score());
            }

            List<TextSegment> segments = filtered.stream()
                    .map(EmbeddingMatch::embedded)
                    .collect(Collectors.toList());
            List<TextSegment> reranked = rerankService.rerank(searchQuery, segments, rerankTopN);
            for (TextSegment seg : reranked) {
                double score = scoreByText.getOrDefault(seg.text(), -1.0);
                result.add(new RetrievedChunk(seg.text(),
                        RetrievedChunk.parseFileName(seg.text()), score));
            }
        }

        if (result.isEmpty()) {
            log.info("检索结果为空（该 query 不会被写入缓存）: userId={}, query={}", userId, searchQuery);
        }

        log.info("检索完成: userId={}, query={}, 最终片段数={}", userId, searchQuery, result.size());
        return result;
    }
}
