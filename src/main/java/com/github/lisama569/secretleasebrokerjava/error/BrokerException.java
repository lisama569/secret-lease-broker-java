package com.github.lisama569.secretleasebrokerjava.error;

/**
 * 服务内所有可预期的业务异常。
 */
public class BrokerException extends RuntimeException {

    private final ErrorCode errorCode;

    public BrokerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public ErrorCategory category() {
        return errorCode.category();
    }
}
