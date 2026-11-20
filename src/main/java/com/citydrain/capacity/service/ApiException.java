package com.citydrain.capacity.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** API 业务异常：由全局处理器转换为 ProblemDetail。 */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, msg);
    }

    public static ApiException unprocessable(String msg) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    public static ApiException badRequest(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }

    public static ApiException conflict(String msg) {
        return new ApiException(HttpStatus.CONFLICT, msg);
    }
}
