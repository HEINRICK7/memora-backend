package com.memora.application.mural;

public final class MuralNotFoundException extends RuntimeException {
    public MuralNotFoundException() {
        super("O mural não está disponível.");
    }
}
