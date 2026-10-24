package com.memora.application.mural;

public final class PhotoNotReadyException extends RuntimeException {
    public PhotoNotReadyException() {
        super("A fotografia ainda não está pronta para ser associada.");
    }
}
