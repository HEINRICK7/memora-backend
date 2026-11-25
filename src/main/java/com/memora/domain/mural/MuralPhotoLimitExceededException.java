package com.memora.domain.mural;

public final class MuralPhotoLimitExceededException extends RuntimeException {
    private final int maxPhotos;

    public MuralPhotoLimitExceededException(int maxPhotos) {
        super("Este mural atingiu o limite máximo de " + maxPhotos + " fotografias.");
        this.maxPhotos = maxPhotos;
    }

    public int maxPhotos() {
        return maxPhotos;
    }
}
