package com.memora.presentation.http;

public final class MuralPhotoRequestException extends RuntimeException {
    public MuralPhotoRequestException() {
        super("Os dados enviados são inválidos.");
    }
}
