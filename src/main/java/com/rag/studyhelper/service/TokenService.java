package com.rag.studyhelper.service;

import com.rag.studyhelper.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 登录会话（Token）服务。
 * <p>
 * <b>会话方案：不透明 token + Redis，而不是 JWT。</b>
 * <ul>
 *   <li>JWT 把用户信息签进 token，服务端无状态——但也就<b>无法主动失效</b>：
 *       用户点"退出登录"或账号被盗要踢人时，JWT 只能等它自然过期，
 *       要不然就得引入黑名单，那又变成有状态了。</li>
 *   <li>不透明 token（随机串）+ Redis：状态在 Redis，
 *       退出登录 = 删 key，立即生效；改密码 = 删该用户所有会话，全端踢下线。</li>
 *   <li>对学习项目还有个好处：能看清"会话到底是什么"——就是 Redis 里一条
 *       {@code token → userId} 的记录加一个过期时间，没有任何魔法。</li>
 * </ul>
 * <p>
 * <b>token 怎么生成？</b>
 * {@link SecureRandom} 取 32 字节随机数再 Base64url 编码（43 个字符）。
 * 熵 256 bit，猜中一个有效 token 的概率可以忽略。
 * <b>不能用 UUID.nextToken / Math.random</b>：后者可预测。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final String KEY_PREFIX = "login:token:";
    private static final String USER_TOKENS_PREFIX = "login:user-tokens:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /** 会话有效期（小时），每次使用滑动续期 */
    @Value("${app.auth.token-ttl-hours:72}")
    private long ttlHours;

    public TokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 为用户签发新 token。
     * 同一用户多端登录互不影响：一个用户可以同时有多个有效 token，
     * 每个 token 是 Redis 里独立的一条记录。
     */
    public String issue(long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redisTemplate.opsForValue().set(key(token), String.valueOf(userId), ttlHours, TimeUnit.HOURS);
        // 维护"该用户的所有活跃 token"集合，退出/踢人时能一键清光
        redisTemplate.opsForSet().add(userTokensKey(userId), token);
        redisTemplate.expire(userTokensKey(userId), ttlHours, TimeUnit.HOURS);
        log.info("签发会话 token: userId={}", userId);
        return token;
    }

    /**
     * 用 token 换 userId；无效/过期返回 null。
     * <p>
     * <b>滑动续期</b>：每次校验都重置 TTL——活跃用户不掉线，
     * 闲置超过 TTL 自动失效，不用定时任务清理（Redis 的过期机制天然兜底）。
     */
    public Long resolve(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(key(token));
        if (value == null) {
            return null;
        }
        try {
            long userId = Long.parseLong(value);
            redisTemplate.expire(key(token), ttlHours, TimeUnit.HOURS);
            return userId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 退出登录：只删当前这个 token（其他端的会话不受影响）。
     */
    public void revoke(String token) {
        if (token == null) {
            return;
        }
        String value = redisTemplate.opsForValue().get(key(token));
        redisTemplate.delete(key(token));
        if (value != null) {
            try {
                redisTemplate.opsForSet().remove(userTokensKey(Long.parseLong(value)), token);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * 踢掉某用户的全部会话（改密码/封号时用）。
     */
    public void revokeAll(long userId) {
        String setKey = userTokensKey(userId);
        java.util.Set<String> tokens = redisTemplate.opsForSet().members(setKey);
        if (tokens != null && !tokens.isEmpty()) {
            java.util.List<String> keys = new java.util.ArrayList<>(tokens.size());
            for (String t : tokens) {
                keys.add(key(t));
            }
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(setKey);
        log.info("已吊销用户全部会话: userId={}", userId);
    }

    private static String key(String token) {
        return KEY_PREFIX + token;
    }

    private static String userTokensKey(long userId) {
        return USER_TOKENS_PREFIX + userId;
    }
}
