package com.rag.studyhelper.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希服务的核心安全属性测试。
 */
class PasswordServiceTest {

    private final PasswordService service = new PasswordService();

    @Test
    void hash_shouldBeSelfVerifying() {
        String hash = service.hash("mySecret123");
        assertTrue(service.verify("mySecret123", hash));
        assertFalse(service.verify("wrongPassword", hash));
    }

    @Test
    void samePassword_shouldProduceDifferentHashes() {
        // 盐是随机生成的：同一密码两次哈希结果必然不同（防彩虹表的关键）
        String h1 = service.hash("password123");
        String h2 = service.hash("password123");
        assertNotEquals(h1, h2);
        // 但两个哈希都能验证同一个明文
        assertTrue(service.verify("password123", h1));
        assertTrue(service.verify("password123", h2));
    }

    @Test
    void hash_shouldNeverContainRawPassword() {
        String hash = service.hash("mySecret123");
        assertFalse(hash.contains("mySecret123"), "哈希串里绝不能出现明文");
        assertTrue(hash.startsWith("pbkdf2:"), "存储格式应自包含算法标识");
    }

    @Test
    void verify_shouldHandleMalformedStoredHash() {
        // 库里的脏数据/被篡改的哈希：verify 必须返回 false 而不是抛异常
        assertFalse(service.verify("anything", "not-a-valid-format"));
        assertFalse(service.verify("anything", ""));
        assertFalse(service.verify("anything", null));
    }

    @Test
    void needsRehash_shouldDetectLowerIterations() {
        assertTrue(service.needsRehash("pbkdf2:1000:ab:cd"), "低迭代次数的旧哈希需要升级");
        assertFalse(service.needsRehash(service.hash("x")), "当前强度的新哈希无需升级");
    }
}
