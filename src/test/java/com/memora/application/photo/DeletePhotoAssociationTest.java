package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MEM-001-R11/R12 and MEM-006-R13/R15: linked photo deletion must not reach MinIO. */
class DeletePhotoAssociationTest {
    @Test
    void doesNotDeleteStorageWhenThePhotoRepositoryRejectsAnAttachedAsset() {
        UUID photoId = UUID.randomUUID();
        PhotoAssetRepository repository = mock(PhotoAssetRepository.class);
        PhotoStorage storage = mock(PhotoStorage.class);
        CurrentOwner owner = () -> "owner-1";
        when(repository.deleteIfUnattached(photoId, owner.ownerId(), Instant.parse("2026-10-24T18:00:00Z")))
                .thenThrow(new PhotoAttachedException());
        DeletePhoto useCase = new DeletePhoto(repository, storage, owner,
                Clock.fixed(Instant.parse("2026-10-24T18:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> useCase.execute(photoId)).isInstanceOf(PhotoAttachedException.class);

        verify(storage, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verify(repository).deleteIfUnattached(photoId, owner.ownerId(), Instant.parse("2026-10-24T18:00:00Z"));
    }
}
