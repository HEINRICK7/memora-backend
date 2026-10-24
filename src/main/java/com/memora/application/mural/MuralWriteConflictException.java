package com.memora.application.mural;

public final class MuralWriteConflictException extends RuntimeException {
    public MuralWriteConflictException() {
        super("A versão do mural mudou durante a atualização.");
    }
}
