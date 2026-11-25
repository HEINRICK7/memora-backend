package com.memora.domain.photo;

public final class PhotoQuotaExceededException extends RuntimeException {
    public PhotoQuotaExceededException(String message) {
        super(message);
    }
}
