package com.memora.domain.mural;

public final class MuralNotEditableException extends RuntimeException {
    public MuralNotEditableException() {
        super("Somente rascunhos podem ser alterados.");
    }
}
