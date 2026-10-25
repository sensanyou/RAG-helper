package com.rag.studyhelper.context;

import com.rag.studyhelper.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * UserContext 的核心契约：不清理就串号。
 */
class UserContextTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void setAndRequire_shouldReturnSameUser() {
        UserContext.set(42L);
        assertEquals(42L, UserContext.requireUserId());
    }

    @Test
    void requireWithoutLogin_shouldThrow401() {
        BusinessException e = assertThrows(BusinessException.class, UserContext::requireUserId);
        assertEquals(401, e.getHttpStatus().value());
    }

    @Test
    void clear_shouldPreventThreadReuseLeak() {
        UserContext.set(1L);
        UserContext.clear();
        // 模拟线程池复用：新请求进来前上下文必须是空的
        assertNull(UserContext.currentOrNull());
    }
}
