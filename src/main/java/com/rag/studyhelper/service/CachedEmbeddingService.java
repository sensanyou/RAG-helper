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


@Service
public class CachedEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(CachedEmbeddingService.class);

    private final EmbeddingModel embeddingModel;


    @Lazy
    @Autowired
    private CachedEmbeddingService selfProxy;

    public CachedEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

   /**这段代码的意思是：
    1. 根据输入文本生成缓存 Key。
    2. 先尝试从配置的缓存中读取结果。
    3. 命中时直接返回已有向量。
    4. 未命中时才调用 Embedding 模型，并缓存返回的向量。**/
    @Cacheable(cacheNames = CacheConfig.CACHE_EMBEDDING,
            key = "T(com.rag.studyhelper.config.CacheKeyGenerator).sha256(#text)")
    public float[] embedVector(String text) {
        log.debug("Embedding 缓存未命中，调用远程 API: len={}", text.length());
        return embeddingModel.embed(text).content().vector();
    }


    public Embedding embed(String text) {
        return Embedding.from(selfProxy.embedVector(text));
    }

/**它还有一个容易忽略的细节：Spring 的缓存注解依赖代理机制。如果在同一个类内部通过 this 调用带有 @Cacheable 的方法，调用通常会绕过代理，导致缓存逻辑不生效。你的项目通过 selfProxy 调用 embedVector() 来处理这个问题。**/
    public List<Embedding> embedAll(List<String> texts) {
        List<Embedding> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(Embedding.from(selfProxy.embedVector(text)));
        }
        return result;
    }
}
