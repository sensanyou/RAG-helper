package com.rag.studyhelper.config;

import dev.langchain4j.model.openai.*;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * LangChain4j 配置
 */
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.chat-model.temperature}")
    private Double temperature;

    @Value("${langchain4j.open-ai.embedding-model.api-key}")
    private String embeddingApiKey;

    @Value("${langchain4j.open-ai.embedding-model.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String embeddingModelName;

    // ── Milvus (large / production) ──

    @Value("${milvus.host:localhost}")
    private String milvusHost;

    @Value("${milvus.port:19530}")
    private Integer milvusPort;

    @Value("${milvus.collection-name:rag_study_helper}")
    private String milvusCollectionName;

    @Value("${milvus.dimension:2048}")
    private Integer milvusDimension;

    @Bean
    @ConditionalOnProperty(name = "vector.store.type", havingValue = "milvus")
    @Lazy
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(milvusCollectionName)
                .dimension(milvusDimension)
                .build();
    }

    // LLM模型选择（要选择适配 OpenAI API 的模型）
    @Bean
    public OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                // 本地的计数器，用来知道当前对话有多长，跟模型实际输出无关
                .tokenizer(new OpenAiTokenizer())
                .build();
    }

    // LLM流式模型（要选择适配 OpenAI API 的模型）
    @Bean
    public OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(chatApiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .tokenizer(new OpenAiTokenizer())
                .build();
    }

    // 向量嵌入模型（要选择适配 OpenAI API 的模型）
    @Bean
    public OpenAiEmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
