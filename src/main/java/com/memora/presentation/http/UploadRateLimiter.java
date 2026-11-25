package com.memora.presentation.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UploadRateLimiter {
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final Duration window;
    private final ConcurrentHashMap<String, WindowBucket> buckets = new ConcurrentHashMap<>();

    public UploadRateLimiter(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    public void checkOrThrow(String key, int maxRequestsPerWindow) {
        if (maxRequestsPerWindow <= 0) {
            return;
        }
        Instant now = clock.instant();
        if (buckets.size() > MAX_TRACKED_KEYS) {
            evictExpired(now);
        }

        WindowBucket updated = buckets.compute(key, (k, existing) -> {
            if (existing == null || !now.isBefore(existing.windowEnd())) {
                return new WindowBucket(now.plus(window), 1);
            }
            return new WindowBucket(existing.windowEnd(), existing.count() + 1);
        });

        if (updated.count() > maxRequestsPerWindow) {
            long retryAfter = Math.max(1L, Duration.between(now, updated.windowEnd()).toSeconds());
            throw new RateLimitExceededException(retryAfter);
        }
    }

    private void evictExpired(Instant now) {
        for (Map.Entry<String, WindowBucket> entry : buckets.entrySet()) {
            if (!now.isBefore(entry.getValue().windowEnd())) {
                buckets.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private record WindowBucket(Instant windowEnd, int count) {
    }
}
