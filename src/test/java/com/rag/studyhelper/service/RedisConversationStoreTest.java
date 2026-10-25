package com.rag.studyhelper.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisConversationStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /**
     * V2 隔离改造的回归测试：key 必须是 session:{userId}:{sessionId}，
     * 锁的粒度同步变化。不同用户用相同 sessionId 也不能互串。
     */
    @Test
    void addTurnUsesLockAndStoresBothMessages() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session:42:s1")).thenReturn(null);
        when(redissonClient.getLock("session:42:s1:lock")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        RedisConversationStore store = new RedisConversationStore(redisTemplate, redissonClient);
        store.addTurn(42L, "s1", "什么是事务？", "事务是一组原子操作。");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("session:42:s1"), json.capture(), eq(1L), eq(java.util.concurrent.TimeUnit.HOURS));
        verify(lock).lock();
        verify(lock).unlock();
        assertTrue(json.getValue().contains("什么是事务？"));
        assertTrue(json.getValue().contains("事务是一组原子操作。"));
    }
}
