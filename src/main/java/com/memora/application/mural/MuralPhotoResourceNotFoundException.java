package com.memora.application.mural;

public final class MuralPhotoResourceNotFoundException extends RuntimeException {
    public MuralPhotoResourceNotFoundException() {
        super("O recurso solicitado não está disponível.");
    }
}
