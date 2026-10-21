package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;
import com.rag.studyhelper.model.RetrievedChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 问答编排服务。
 * <p>
 * 这个类只负责"流程编排"，具体环节都委托给独立服务：
 * <ul>
 *   <li>问题重写 → {@link QueryRewriteService}</li>
 *   <li>向量化 → {@link CachedEmbeddingService}（带 Redis 缓存）</li>
 *   <li>向量检索 + 阈值过滤 + 重排 → {@link RetrievalService}（带 Redis 缓存）</li>
 *   <li>多轮上下文 → {@link ConversationStore}</li>
 * </ul>
 * <p>
 * <b>为什么检索要单独拆到 RetrievalService？</b>
 * 因为 Spring Cache（{@code @Cacheable}）依赖 AOP 代理，
 * 而"同一个类内部调用自己的方法"会走 this 引用、绕过代理、注解静默失效。
 * 最初把 retrieve() 写在本类里，结果缓存从来没命中过、Redis 里一个 key 都没有、
 * 也不报任何错——这是最难排查的一类 bug。
 * 拆成独立 Bean 后，调用跨过代理边界，缓存才真正生效。
 */
@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private OpenAiStreamingChatModel streamingChatModel;

    @Autowired
    private CachedEmbeddingService cachedEmbeddingService;

    @Autowired
    private RetrievalService retrievalService;

    public void streamAnswer(String sessionId, String question, StreamingResponseHandler<AiMessage> callback) {
        // 获取历史上下文
        List<ChatMessage> history = conversationStore.getHistory(sessionId);
        // 提问小于 5 个字，进行问题重写，为了使 RAG 检索更准确
        // 比如 RAG 场景下 你通过 《如何学习JAVA》 这个文档去检索（如果不了解向量数据库和embedding就先别管，只看问题）
        // 你第一次问："java 要学什么框架"
        // embedding 根据 "java 要学什么框架" 转成向量去检索到了相应的内容再放进 prompt 喂给 LLM
        // LLM 根据文档说："springboot"
        // 你第二次问："他有什么好处"
        // 我们可以一眼就看出这里的他指的是 springboot ，但是 embedding 模型不知道
        // embedding 只是把你输入的 "他有什么好处" 转换成向量去向量数据库查询，所以查出来的根本就不是你想要的文档内容
        // 这时 LLM 就不会根据文档去生成你想要的内容了
        String searchQuery = queryRewriteService.rewrite(question, history, 5);
        if (!searchQuery.equals(question)) {
            log.info("Search query rewritten: \"{}\" → \"{}\"", question, searchQuery);
        }

        // embedding（向量嵌入模型）根据你的问题转换成向量
        // 走带缓存的服务：同一个问题问第二遍时不会重复消耗 Embedding 额度
        Embedding questionEmbedding = cachedEmbeddingService.embed(searchQuery);

        // 查向量库：20 条候选 → 阈值过滤 → Rerank 精选 5 条
        // 整段检索结果带缓存（只缓存"检索到的片段"，不缓存最终答案）
        // 注意这里是通过注入的 Bean 调用，跨过 Spring 代理边界，@Cacheable 才会生效
        List<RetrievedChunk> relevant = retrievalService.retrieve(searchQuery, questionEmbedding);

        // 自定义 prompt 模板，如果检索结果为空，则使用普通对话模式
        String prompt;
        if (relevant.isEmpty()) {
            log.info("No relevant docs found, using normal chat mode");
            prompt = "你是一个智能助手。请回答用户的问题。\n\n"
                    + "## 问题\n" + question;
        } else {
            String context = relevant.stream()
                    .map(RetrievedChunk::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            prompt = "## 角色\n"
                    + "你是一个基于内部文档的数据分析助手。\n\n"
                    + "## 参考文档\n"
                    + context + "\n\n"
                    + "## 约束\n"
                    + "- 回答时请标注信息来源，格式：根据 [来源:文件名] 的记载/显示...\n"
                    + "- 严格基于参考文档回答，不要使用你自己的知识\n"
                    + "- 如果参考文档中没有相关信息：\n"
                    + "  - 完全不相关：回复\"根据文档内容，没有找到相关信息\"\n"
                    + "  - 部分相关：说明文档中涉及了什么，明确指出未涉及的部分\n"
                    + "- 回答时引用具体的行/数据来支撑你的结论\n"
                    + "- 用中文回答\n\n"
                    + "## 问题\n" + question;
        }

        // 使用 langChain 调用适配 OpenAI API 的模型生成答案
        StringBuilder fullAnswer = new StringBuilder();
        streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                callback.onNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                TokenUsage usage = response.tokenUsage();
                if (usage != null) {
                    log.info("Token 用量 - 输入: {}, 输出: {}, 总和: {}",
                            usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
                }
                // 储存上下文
                conversationStore.addTurn(sessionId, question, fullAnswer.toString());
                callback.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }
}
