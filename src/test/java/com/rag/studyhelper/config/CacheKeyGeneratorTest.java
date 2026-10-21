package com.rag.studyhelper.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证缓存 key 表达式与 SHA-256 工具的正确性。
 * <p>
 * <b>为什么要单独测这个？</b>
 * 因为 {@code @Cacheable(key = "T(...).sha256(#param)")} 里的表达式
 * 是<b>字符串</b>，编译器完全不检查。一旦写错（方法名拼错、参数名取不到），
 * 运行时才抛异常，而且默认会被缓存切面吞掉——表现为"缓存永远不生效"，
 * 极难排查。用单元测试提前锁住这个契约。
 */
class CacheKeyGeneratorTest {

    @Test
    void sha256_shouldBeStableAndFixedLength() {
        String a = CacheKeyGenerator.sha256("Spring Boot 有哪些核心特性");
        String b = CacheKeyGenerator.sha256("Spring Boot 有哪些核心特性");

        // 同样的输入必须得到同样的 key，否则缓存永远命不中
        assertEquals(a, b, "相同输入必须产生相同摘要");
        // SHA-256 十六进制固定 64 位
        assertEquals(64, a.length(), "SHA-256 十六进制应为 64 字符");

        // 不同输入必须不同，否则会串数据
        assertNotEquals(a, CacheKeyGenerator.sha256("Spring Boot 的默认端口"));
    }

    @Test
    void sha256_shouldHandleChineseAndSpecialChars() {
        // 中文、空格、换行、标点都要能正常处理（UTF-8 编码）
        String key = CacheKeyGenerator.sha256("[来源:测试.pdf]\n内容：含中文，标点！@#$%");
        assertEquals(64, key.length());
        assertTrue(key.matches("[0-9a-f]{64}"), "只能包含小写十六进制字符");
    }

    @Test
    void sha256_nullShouldNotThrow() {
        assertEquals("null", CacheKeyGenerator.sha256(null));
    }

    /**
     * 用真实的 SpEL 解析器执行一次缓存 key 表达式，
     * 确认 {@code T(com.rag...CacheKeyGenerator).sha256(#text)} 这种写法能跑通。
     * <p>
     * 这比只测 sha256 方法更有价值：它验证的是「注解里那串字符串」本身。
     */
    @Test
    void spelExpression_shouldResolve() throws Exception {
        SpelExpressionParser parser = new SpelExpressionParser();
        StandardEvaluationContext ctx = new StandardEvaluationContext();

        Method method = DemoService.class.getMethod("embed", String.class);
        ctx.setVariable("text", "Spring Boot 有哪些核心特性");

        Object value = parser
                .parseExpression("T(com.rag.studyhelper.config.CacheKeyGenerator).sha256(#text)")
                .getValue(ctx);

        assertEquals(CacheKeyGenerator.sha256("Spring Boot 有哪些核心特性"), value);
        assertTrue(value instanceof String);
    }

    /**
     * 用于反射取方法签名的占位类。
     */
    static class DemoService {
        public String embed(String text) {
            return text;
        }
    }
}
