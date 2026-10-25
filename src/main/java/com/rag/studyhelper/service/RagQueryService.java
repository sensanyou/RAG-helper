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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * RAG 问答编排服务。
 * <p>
 * 这个类只负责"流程编排"，具体环节都委托给独立服务：
 * <ul>
 *   <li>问题重写 → {@link QueryRewriteService}</li>
 *   <li>向量化 → {@link CachedEmbeddingService}（带 Redis 缓存）</li>
 *   <li>向量检索 + 阈值过滤 + 重排 → {@link RetrievalService}（带 Redis 缓存 + 用户隔离）</li>
 *   <li>多轮上下文 → {@link ConversationStore}</li>
 * </ul>
 * <p>
 * <b>V2 变更</b>：
 * <ul>
 *   <li>全链路带 userId——检索按用户过滤，会话按用户隔离；</li>
 *   <li>新增 {@code onRetrieved} 回调：检索完成后、生成开始前，
 *       把命中的片段（含相似度分数）交给调用方。
 *       Controller 用它通过 SSE 的 {@code sources} 事件把引用先推给前端，
 *       实现"回答可追溯"——用户看到答案的同时能看到它依据了哪些资料。</li>
 * </ul>
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

    /**
     * @param sessionId    会话 ID（前端生成；隔离由 userId 保证）
     * @param userId       当前用户（认证拦截器解析，非前端传入）
     * @param question     用户问题
     * @param onRetrieved  检索完成回调，可为 null；用于推送引用来源
     * @param callback     LLM 流式输出回调
     */
    public void streamAnswer(long userId, String sessionId, String question,
                             Consumer<List<RetrievedChunk>> onRetrieved,
                             StreamingResponseHandler<AiMessage> callback) {
        // 获取历史上下文（按用户隔离的会话历史）
        List<ChatMessage> history = conversationStore.getHistory(userId, sessionId);
        // 提问小于 5 个字，进行问题重写，为了使 RAG 检索更准确
        // 场景：第一次问"java 要学什么框架"，模型回答"springboot"；
        // 第二次问"他有什么好处"——人知道"他"指 springboot，但 embedding 不知道，
        // 直接拿"他有什么好处"转向量检索，查出来的内容驴唇不对马嘴。
        // 重写服务用历史上下文把指代补全后再检索。
        String searchQuery = queryRewriteService.rewrite(question, history, 5);
        if (!searchQuery.equals(question)) {
            log.info("Search query rewritten: \"{}\" → \"{}\"", question, searchQuery);
        }

        // embedding（向量嵌入模型）根据你的问题转换成向量
        // 走带缓存的服务：同一个问题问第二遍时不会重复消耗 Embedding 额度
        Embedding questionEmbedding = cachedEmbeddingService.embed(searchQuery);

        // 查向量库：20 条候选（仅本人+系统文档）→ 阈值过滤 → Rerank 精选 5 条
        // 注意：通过注入的 Bean 调用，跨过 Spring 代理边界，@Cacheable 才会生效
        List<RetrievedChunk> relevant = retrievalService.retrieve(userId, searchQuery, questionEmbedding);

        // 检索完成 → 先把引用来源（含分数）推给前端，再开始生成。
        // 用户在等待生成的同时就能看到"模型即将依据哪些资料"，回答可追溯。
        if (onRetrieved != null) {
            try {
                onRetrieved.accept(relevant);
            } catch (Exception e) {
                log.warn("推送 sources 事件失败（不影响生成）: {}", e.getMessage());
            }
        }

        // 自定义 prompt 模板
        String prompt;
        if (relevant.isEmpty()) {
            log.info("No relevant docs found, using normal chat mode");
            prompt = "你是一个智能助手。请回答用户的问题。"
                    + "如果你不确定或缺乏依据，请明确说明，不要编造。\n\n"
                    + "## 问题\n" + question;
        } else {
            String context = relevant.stream()
                    .map(RetrievedChunk::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            String sources = relevant.stream()
                    .map(c -> String.format("%s（相似度 %.2f）", c.getFileName(), c.getScore()))
                    .collect(Collectors.joining("；"));

            prompt = "## 角色\n"
                    + "你是一个基于内部文档的学习助手。\n\n"
                    + "## 参考文档\n"
                    + context + "\n\n"
                    + "## 本次命中的资料\n"
                    + sources + "\n\n"
                    + "## 约束\n"
                    + "- 回答时请标注信息来源，格式：根据 [来源:文件名] 的记载/显示...\n"
                    + "- 严格基于参考文档回答，不要使用你自己的知识\n"
                    + "- 如果参考文档中没有相关信息：\n"
                    + "  - 完全不相关：回复\"根据文档内容，没有找到相关信息\"\n"
                    + "  - 部分相关：说明文档中涉及了什么，明确指出未涉及的部分\n"
                    + "- 宁可回答\"资料不足\"，也不要编造文档里没有的内容\n"
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
                // 储存上下文（按用户隔离）
                conversationStore.addTurn(userId, sessionId, question, fullAnswer.toString());
                callback.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }
}
