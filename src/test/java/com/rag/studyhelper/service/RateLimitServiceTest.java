package com.rag.studyhelper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RRateLimiter rateLimiter;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(redissonClient, redisTemplate);
    }

    @Test
    void initializesRateLimiterBeforeAcquire() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(rateLimiter.isExists()).thenReturn(false);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        assertTrue(rateLimitService.tryAcquire("chat", 10, 60, 24));

        verify(rateLimiter).trySetRate(
                eq(RateType.OVERALL),
                eq(10L),
                eq(Duration.ofSeconds(60)),
                eq(Duration.ofHours(24))
        );
    }

    @Test
    void firstDailyCallSetsExpiration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertTrue(rateLimitService.tryDaily("chat", 10));

        verify(redisTemplate).expire(anyString(), eq(28L), eq(TimeUnit.HOURS));
    }

    @Test
    void dailyLimitRejectsCallAboveMaximum() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(11L);

        assertFalse(rateLimitService.tryDaily("chat", 10));
    }
}
