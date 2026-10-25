package com.rag.studyhelper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.config.IpRateLimit;
import com.rag.studyhelper.context.UserContext;
import com.rag.studyhelper.model.ChatRequest;
import com.rag.studyhelper.model.RetrievedChunk;
import com.rag.studyhelper.service.RagQueryService;
import com.rag.studyhelper.utils.Results;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;

/**
 * 聊天接口（V2：按用户隔离会话与检索 + 引用来源可追溯）。
 * <p>
 * <b>SSE 事件协议（四个通道）：</b>
 * <ul>
 *   <li>默认事件：{@code {"token":"..."}} —— 生成的增量文本；</li>
 *   <li>{@code sources}：检索完成时推一次，
 *       {@code {"sources":[{"fileName","score"}...]}} —— 本次回答依据的资料；
 *       先于第一个 token 到达，用户在等待生成时就能看到引用；</li>
 *   <li>{@code error}：出错时推错误信息；</li>
 *   <li>{@code [DONE]}：结束哨兵。</li>
 * </ul>
 * <b>userId 从 UserContext 取</b>，会话历史与检索结果都按它隔离。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private RagQueryService ragQueryService;


    @IpRateLimit("chat")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        long userId = UserContext.requireUserId();
        // 与前端交互的 SSE 响应
        SseEmitter emitter = new SseEmitter(60_000L);

        try {
            ragQueryService.streamAnswer(userId, request.getSessionId(), request.getQuestion(),
                    // 检索完成回调：把引用来源（文件名 + 相似度分数）先推给前端
                    relevant -> sendSources(emitter, relevant),
                    new StreamingResponseHandler<AiMessage>() {
                // 处理 LLM 返回的分词结果
                @Override
                public void onNext(String token) {
                    try {
                        Map<String, String> data = new HashMap<>();
                        data.put("token", token);
                        // 发送 json 格式给前端
                        emitter.send(SseEmitter.event()
                                .data(OBJECT_MAPPER.writeValueAsString(data)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                // 处理 SSE 结束信息
                @Override
                public void onComplete(Response<AiMessage> response) {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        // 关闭 SSE 连接
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                // 处理失败信息
                @Override
                public void onError(Throwable error) {
                    log.error("LLM 流式处理失败", error);
                    try {
                        // 错误信息通过 SSE 响应返回给前端
                        Results<Void> err = Results.failed("500", "流式处理失败: " + error.getMessage());
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(OBJECT_MAPPER.writeValueAsString(err)));
                        emitter.send(SseEmitter.event().data("[DONE]"));
                    } catch (IOException e2) {
                        emitter.completeWithError(e2);
                    }
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            log.error("chat 流式处理失败", e);
            try {
                Results<Void> err = Results.failed("500", "流式处理失败: " + e.getMessage());
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(OBJECT_MAPPER.writeValueAsString(err)));
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }

        return emitter;
    }

    /**
     * 推送引用来源事件。
     * <p>
     * 只发"文件名 + 分数"的摘要，不把片段全文再发一遍——
     * 全文已经在回答里被模型引用了，重复推送浪费带宽。
     */
    private void sendSources(SseEmitter emitter, List<RetrievedChunk> relevant) {
        try {
            List<Map<String, Object>> sources = new ArrayList<>();
            if (relevant != null) {
                for (RetrievedChunk c : relevant) {
                    Map<String, Object> s = new HashMap<>();
                    s.put("fileName", c.getFileName());
                    s.put("score", Math.round(c.getScore() * 1000) / 1000.0);
                    sources.add(s);
                }
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("sources", sources);
            emitter.send(SseEmitter.event()
                    .name("sources")
                    .data(OBJECT_MAPPER.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("发送 sources 事件失败: {}", e.getMessage());
        }
    }
}
