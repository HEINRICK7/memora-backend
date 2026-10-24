package com.memora.domain.photo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadMetadataTest {
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Test
    void acceptsSupportedImageTypesWithinTheConfiguredLimit() {
        assertThat(UploadMetadata.request("image/jpeg", 1, MAX_BYTES).contentType())
                .isEqualTo("image/jpeg");
        assertThat(UploadMetadata.request("image/png", MAX_BYTES, MAX_BYTES).sizeBytes())
                .isEqualTo(MAX_BYTES);
    }

    @Test
    void rejectsUnsupportedImageType() {
        assertThatThrownBy(() -> UploadMetadata.request("image/webp", 100, MAX_BYTES))
                .isInstanceOf(PhotoValidationException.class);
    }

    @Test
    void rejectsEmptyOrOversizedImage() {
        assertThatThrownBy(() -> UploadMetadata.request("image/png", 0, MAX_BYTES))
                .isInstanceOf(PhotoValidationException.class);
        assertThatThrownBy(() -> UploadMetadata.request("image/png", MAX_BYTES + 1, MAX_BYTES))
                .isInstanceOf(PhotoValidationException.class);
    }
}
