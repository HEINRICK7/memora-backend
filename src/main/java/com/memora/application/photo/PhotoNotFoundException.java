package com.memora.application.photo;

public final class PhotoNotFoundException extends RuntimeException {
    public PhotoNotFoundException() {
        super("A fotografia não está disponível.");
    }
}
