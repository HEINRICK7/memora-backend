package com.memora.domain.photo;

public final class UploadExpiredException extends RuntimeException {
    public UploadExpiredException() {
        super("A intenção de upload expirou.");
    }
}
