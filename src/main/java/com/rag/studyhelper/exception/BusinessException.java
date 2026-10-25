package com.rag.studyhelper.exception;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常。
 * 异常类型本身携带 HTTP 状态和业务码，避免 Controller 重复编写错误返回逻辑。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String code;

    public BusinessException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public BusinessException(HttpStatus httpStatus, String code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "400", message);
    }

    /** 401：未登录或凭证失效。区别于 403（登录了但没权限） */
    public static BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "401", message);
    }

    /** 403：已登录，但资源不属于你。绝不泄露资源是否存在——统一说"无权访问" */
    public static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, "403", message);
    }

    public static BusinessException internal(String message) {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "500", message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "404", message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "409", message);
    }

    public static BusinessException tooLarge(String message) {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "413", message);
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
