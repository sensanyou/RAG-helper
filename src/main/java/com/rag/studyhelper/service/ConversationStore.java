package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;

import java.util.List;

/**
 * 对话历史存储接口。当前使用 Redis 实现。
 * <p>
 * <b>所有方法都要求显式传入 userId。</b>
 * V1 的 key 只有 sessionId（前端生成），任何人拿到别人的 sessionId 就能读/写
 * 对方的对话历史。V2 把 userId 编进 key：
 * {@code session:{userId}:{sessionId}} —— 即便 sessionId 泄露，
 * 它只对所属用户有效。userId 由认证拦截器从 token 解出，前端无法伪造。
 */
public interface ConversationStore {

    List<ChatMessage> getHistory(long userId, String sessionId);

    void addTurn(long userId, String sessionId, String userMessage, String assistantMessage);

    void clear(long userId, String sessionId);
}
