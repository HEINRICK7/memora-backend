package com.memora.domain.photo;

public final class PhotoValidationException extends RuntimeException {
    public enum Code {
        UNSUPPORTED_MEDIA_TYPE,
        INVALID_SIZE,
        SIZE_LIMIT_EXCEEDED,
        INVALID_CONTENT
    }

    private final Code code;

    public PhotoValidationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
