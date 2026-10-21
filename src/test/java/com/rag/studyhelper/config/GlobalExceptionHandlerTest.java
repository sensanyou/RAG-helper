package com.rag.studyhelper.config;

import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.utils.Results;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionKeepsHttpStatusAndBusinessCode() {
        ResponseEntity<Results<Void>> response = handler.handleBusinessException(
                BusinessException.conflict("相同文档正在处理中")
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("409", response.getBody().getResCode());
        assertEquals("相同文档正在处理中", response.getBody().getMsg());
    }

    @Test
    void illegalArgumentMapsToBadRequest() {
        ResponseEntity<Results<Void>> response = handler.handleIllegalArgument(
                new IllegalArgumentException("unsupported file")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("400", response.getBody().getResCode());
    }
}
