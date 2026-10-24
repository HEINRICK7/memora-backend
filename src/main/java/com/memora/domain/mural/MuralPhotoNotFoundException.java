package com.memora.domain.mural;

public final class MuralPhotoNotFoundException extends RuntimeException {
    public MuralPhotoNotFoundException() {
        super("A fotografia não está associada a este mural.");
    }
}
