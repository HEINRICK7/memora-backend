package com.memora.application.photo;

public final class PhotoAttachedException extends RuntimeException {
    public PhotoAttachedException() {
        super("A fotografia ainda está associada a um mural.");
    }
}
