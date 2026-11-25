package com.memora.domain.mural;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MuralPhotoCollectionLimitTest {
    @Test
    void enforcesMaxPhotosPerMuralAndAllowsIdempotentAppendAtLimit() {
        UUID muralId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        MuralPhotoCollection collection = MuralPhotoCollection.empty(muralId)
                .append(first, 2)
                .append(second, 2);

        // Idempotent re-append of an existing photo at capacity is allowed.
        assertThat(collection.append(first, 2)).isSameAs(collection);

        // Adding a new photo beyond capacity fails with domain limit exception.
        assertThatThrownBy(() -> collection.append(third, 2))
                .isInstanceOf(MuralPhotoLimitExceededException.class);
    }
}
