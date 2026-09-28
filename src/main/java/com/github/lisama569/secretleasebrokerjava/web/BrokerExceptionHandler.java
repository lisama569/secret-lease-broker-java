package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.error.BrokerException;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCategory;
import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务错误码映射为可区分的 HTTP 响应：
 * 403=策略拒绝，410=租约已失效（过期/已吊销），400=参数非法，
 * 404=不存在，409=冲突（并发修改/重复签发）。
 */
@RestControllerAdvice
public class BrokerExceptionHandler {

    @ExceptionHandler(BrokerException.class)
    public ResponseEntity<ErrorResponse> handleBroker(BrokerException ex) {
        HttpStatus status = statusForCategory(ex.category());
        return ResponseEntity.status(status).body(new ErrorResponse(
                ex.errorCode().name(), ex.category().name(), ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleIllegalArgument(Exception ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                ErrorCode.INVALID_PARAM.name(),
                ErrorCategory.INVALID_PARAM.name(),
                ex.getMessage()));
    }

    static HttpStatus statusForCategory(ErrorCategory category) {
        return switch (category) {
            case POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case LEASE_STATE -> HttpStatus.GONE;
            case INVALID_PARAM -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
