package com.memora.presentation.http;

public final class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Muitas solicitações em pouco tempo. Aguarde alguns segundos e tente novamente.");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
