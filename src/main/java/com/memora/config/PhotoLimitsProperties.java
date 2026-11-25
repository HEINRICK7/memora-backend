package com.memora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.limits")
public record PhotoLimitsProperties(
        Integer maxPhotosPerOwner,
        Integer maxPhotosPerMural,
        Integer maxContributionsPerGrant,
        Integer ownerUploadsPerMinute,
        Integer guestContributionsPerMinute,
        Integer orphanCleanupBatchSize
) {
    public int effectiveMaxPhotosPerOwner() {
        return maxPhotosPerOwner == null || maxPhotosPerOwner <= 0 ? 500 : maxPhotosPerOwner;
    }

    public int effectiveMaxPhotosPerMural() {
        return maxPhotosPerMural == null || maxPhotosPerMural <= 0 ? 300 : maxPhotosPerMural;
    }

    public int effectiveMaxContributionsPerGrant() {
        return maxContributionsPerGrant == null || maxContributionsPerGrant <= 0 ? 200 : maxContributionsPerGrant;
    }

    public int effectiveOwnerUploadsPerMinute() {
        return ownerUploadsPerMinute == null || ownerUploadsPerMinute <= 0 ? 60 : ownerUploadsPerMinute;
    }

    public int effectiveGuestContributionsPerMinute() {
        return guestContributionsPerMinute == null || guestContributionsPerMinute <= 0 ? 30 : guestContributionsPerMinute;
    }

    public int effectiveOrphanCleanupBatchSize() {
        return orphanCleanupBatchSize == null || orphanCleanupBatchSize <= 0 ? 100 : orphanCleanupBatchSize;
    }
}
