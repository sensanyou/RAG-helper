package com.rag.studyhelper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.ChatMessage;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话存储服务（Redis 实现，按用户隔离）。
 * <p>
 * <b>V2 隔离改造</b>：key 从 {@code session:{sessionId}} 改为
 * {@code session:{userId}:{sessionId}}。锁的粒度同步调整——
 * 两个用户用相同 sessionId 也不会互锁。
 * key 的拼接规则收敛在 {@link #key(long, String)} 一个私有方法里，
 * 调用方不允许自行拼 key，防止绕过隔离。
 */
@Service
public class RedisConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);
    // 最大轮次 超出删除（用户输入 + LLM输出 为一轮）
    private static final int MAX_ROUNDS = 10;
    // 一小时超时时间
    private static final int TTL_HOURS = 1;
    private static final String KEY_PREFIX = "session:";

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public RedisConversationStore(StringRedisTemplate redisTemplate, RedissonClient redissonClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper();
    }

    /** key 规则统一在这里，调用方不允许自行拼 key（防止绕过隔离） */
    private static String key(long userId, String sessionId) {
        return KEY_PREFIX + userId + ":" + sessionId;
    }

    /**
     * 获取上下文
     */
    @Override
    public List<ChatMessage> getHistory(long userId, String sessionId) {
        String json = redisTemplate.opsForValue().get(key(userId, sessionId));
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize history for user={} session={}", userId, sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 添加轮次
     */
    @Override
    public void addTurn(long userId, String sessionId, String userMessage, String assistantMessage) {
        RLock lock = redissonClient.getLock(key(userId, sessionId) + ":lock");
        lock.lock();
        try {
            List<ChatMessage> history = new ArrayList<>(getHistory(userId, sessionId));
            history.add(new ChatMessage("user", userMessage));
            history.add(new ChatMessage("assistant", assistantMessage));

            int maxMessages = MAX_ROUNDS * 2;
            while (history.size() > maxMessages) {
                history.remove(0);
            }

            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key(userId, sessionId), json, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("Failed to save history for user={} session={}", userId, sessionId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 清空上下文历史
     */
    @Override
    public void clear(long userId, String sessionId) {
        RLock lock = redissonClient.getLock(key(userId, sessionId) + ":lock");
        lock.lock();
        try {
            redisTemplate.delete(key(userId, sessionId));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
