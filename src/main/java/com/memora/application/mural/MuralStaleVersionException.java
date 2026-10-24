package com.memora.application.mural;

public final class MuralStaleVersionException extends RuntimeException {
    public MuralStaleVersionException() {
        super("A versão do mural mudou desde a última leitura.");
    }
}
