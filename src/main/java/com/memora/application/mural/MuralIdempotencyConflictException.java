package com.memora.application.mural;

public final class MuralIdempotencyConflictException extends RuntimeException {
    public MuralIdempotencyConflictException() {
        super("A chave de idempotência já foi usada com outro conteúdo.");
    }
}
