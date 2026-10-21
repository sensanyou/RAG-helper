package com.rag.studyhelper.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRewriteServiceTest {

    @Test
    void shortQuestionNeedsRewrite() {
        assertTrue(QueryRewriteService.needsRewrite("Java", 5));
    }

    @Test
    void pronounQuestionNeedsRewrite() {
        assertTrue(QueryRewriteService.needsRewrite("它有什么好处", 5));
    }

    @Test
    void completeQuestionDoesNotNeedRewrite() {
        assertFalse(QueryRewriteService.needsRewrite("Spring Boot 事务的传播机制是什么", 5));
    }
}
