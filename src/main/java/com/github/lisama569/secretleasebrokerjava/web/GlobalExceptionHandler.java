package com.github.lisama569.secretleasebrokerjava.web;

import com.github.lisama569.secretleasebrokerjava.error.ErrorCode;
import com.github.lisama569.secretleasebrokerjava.error.ServiceException;
import com.github.lisama569.secretleasebrokerjava.web.dto.Dtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务错误码稳定映射为 HTTP 状态，响应体始终包含 errorCode 与解释。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Dtos.ErrorResponse> handle(ServiceException ex) {
        return ResponseEntity.status(toHttpStatus(ex.getCode()))
                .body(new Dtos.ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new Dtos.ErrorResponse(ErrorCode.INVALID_PARAMETER.name(),
                        "请求体无法解析: " + ex.getMostSpecificCause().getMessage()));
    }

    static HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case INVALID_PARAMETER, SECRET_NOT_FOUND -> HttpStatus.BAD_REQUEST;
            case POLICY_DENIED -> HttpStatus.FORBIDDEN;
            case LEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LEASE_NOT_ACTIVE, LEASE_ALREADY_REVOKED, SECRET_VERSION_MISMATCH
                    -> HttpStatus.CONFLICT;
            case CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
        };
    }
}
