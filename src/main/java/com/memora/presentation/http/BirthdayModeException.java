package com.memora.presentation.http;

import org.springframework.http.HttpStatus;

public final class BirthdayModeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BirthdayModeException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }
}
