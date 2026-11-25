package com.memora.infrastructure.jobs;

import com.memora.application.photo.CleanupOrphanUploads;
import com.memora.config.PhotoLimitsProperties;
import com.memora.infrastructure.persistence.BirthdayModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrphanUploadCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(OrphanUploadCleanupJob.class);

    private final CleanupOrphanUploads cleanupOwnerUploads;
    private final BirthdayModeService birthdayModeService;
    private final int batchSize;

    public OrphanUploadCleanupJob(
            CleanupOrphanUploads cleanupOwnerUploads,
            BirthdayModeService birthdayModeService,
            PhotoLimitsProperties limits
    ) {
        this.cleanupOwnerUploads = cleanupOwnerUploads;
        this.birthdayModeService = birthdayModeService;
        this.batchSize = limits.effectiveOrphanCleanupBatchSize();
    }

    @Scheduled(fixedDelayString = "${memora.storage.orphan-cleanup-interval-ms:900000}")
    public void cleanupExpiredUploads() {
        int ownerCleaned = cleanupOwnerUploads.execute(batchSize);
        int guestCleaned = birthdayModeService.cleanupExpiredContributions(batchSize);
        if (ownerCleaned > 0 || guestCleaned > 0) {
            log.info("Orphan upload cleanup completed ownerCleaned={} guestCleaned={}", ownerCleaned, guestCleaned);
        }
    }
}
