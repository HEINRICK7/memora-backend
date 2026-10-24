package com.memora.domain.photo;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoAssetTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void onlyMarksAnUploadingAssetReadyAfterMatchingStoredImageIsInspected() {
        UploadMetadata expected = UploadMetadata.request("image/jpeg", 128, 20L * 1024 * 1024);
        PhotoAsset uploading = PhotoAsset.beginUpload(
                UUID.randomUUID(), "local-development-owner", "originals/private/opaque-key",
                expected, CREATED_AT, CREATED_AT.plusSeconds(900)
        );

        PhotoAsset ready = uploading.confirm(
                new InspectedImage("image/jpeg", 128, 640, 480), CREATED_AT.plusSeconds(10)
        );

        assertThat(uploading.status()).isEqualTo(PhotoStatus.UPLOADING);
        assertThat(ready.status()).isEqualTo(PhotoStatus.READY);
        assertThat(ready.width()).isEqualTo(640);
        assertThat(ready.height()).isEqualTo(480);
    }

    @Test
    void rejectsStoredBytesThatDoNotMatchTheIntent() {
        UploadMetadata expected = UploadMetadata.request("image/png", 128, 20L * 1024 * 1024);
        PhotoAsset uploading = PhotoAsset.beginUpload(
                UUID.randomUUID(), "local-development-owner", "originals/private/opaque-key",
                expected, CREATED_AT, CREATED_AT.plusSeconds(900)
        );

        assertThatThrownBy(() -> uploading.confirm(
                new InspectedImage("image/jpeg", 128, 640, 480), CREATED_AT.plusSeconds(10)
        )).isInstanceOf(PhotoValidationException.class);
        assertThat(uploading.status()).isEqualTo(PhotoStatus.UPLOADING);
    }

    @Test
    void refusesExpiredUploadIntent() {
        UploadMetadata expected = UploadMetadata.request("image/jpeg", 128, 20L * 1024 * 1024);
        PhotoAsset uploading = PhotoAsset.beginUpload(
                UUID.randomUUID(), "local-development-owner", "originals/private/opaque-key",
                expected, CREATED_AT, CREATED_AT.plusSeconds(900)
        );

        assertThatThrownBy(() -> uploading.confirm(
                new InspectedImage("image/jpeg", 128, 640, 480), CREATED_AT.plusSeconds(901)
        )).isInstanceOf(UploadExpiredException.class);
    }
}
