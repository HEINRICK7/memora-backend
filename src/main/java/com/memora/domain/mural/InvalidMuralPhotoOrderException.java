package com.memora.domain.mural;

public final class InvalidMuralPhotoOrderException extends RuntimeException {
    public InvalidMuralPhotoOrderException() {
        super("A ordem precisa incluir exatamente uma vez cada fotografia do mural.");
    }
}
