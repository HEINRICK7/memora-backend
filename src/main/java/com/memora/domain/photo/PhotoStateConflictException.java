package com.memora.domain.photo;

public final class PhotoStateConflictException extends RuntimeException {
    public PhotoStateConflictException(String message) {
        super(message);
    }
}
