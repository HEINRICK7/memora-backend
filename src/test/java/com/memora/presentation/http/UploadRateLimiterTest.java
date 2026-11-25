package com.memora.presentation.http;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadRateLimiterTest {
    @Test
    void enforcesWindowQuotaAndResetsAfterWindowElapses() {
        AtomicReference<Instant> currentTime = new AtomicReference<>(Instant.parse("2026-11-25T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime.get(); }
        };

        UploadRateLimiter limiter = new UploadRateLimiter(clock, Duration.ofSeconds(60));
        limiter.checkOrThrow("guest:token1:127.0.0.1", 2);
        limiter.checkOrThrow("guest:token1:127.0.0.1", 2);

        assertThatThrownBy(() -> limiter.checkOrThrow("guest:token1:127.0.0.1", 2))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).retryAfterSeconds()).isPositive());

        // Different key is unaffected
        limiter.checkOrThrow("guest:token1:10.0.0.2", 2);

        // Window passes -> allowed again
        currentTime.set(currentTime.get().plusSeconds(61));
        limiter.checkOrThrow("guest:token1:127.0.0.1", 2);
    }
}
