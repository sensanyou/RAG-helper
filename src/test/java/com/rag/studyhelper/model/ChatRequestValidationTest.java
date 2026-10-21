package com.rag.studyhelper.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validRequestHasNoViolations() {
        ChatRequest request = new ChatRequest("session-1", "Spring Boot 事务怎么用？");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void blankFieldsAreRejected() {
        ChatRequest request = new ChatRequest(" ", "");

        Set<String> messages = validator.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());

        assertEquals(2, messages.size());
        assertTrue(messages.contains("sessionId 不能为空"));
        assertTrue(messages.contains("question 不能为空"));
    }
}
