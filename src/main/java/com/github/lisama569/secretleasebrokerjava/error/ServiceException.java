package com.github.lisama569.secretleasebrokerjava.error;

/**
 * 业务异常：携带稳定的 {@link ErrorCode} 与面向人的解释信息。
 */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ServiceException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
