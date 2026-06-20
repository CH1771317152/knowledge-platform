package com.platform.common.exception;

public class PlatformException extends RuntimeException {
    private final ErrorCode errorCode;

    public PlatformException(String message) {
        this(ErrorCode.COMMON_BAD_REQUEST, message);
    }

    public PlatformException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
