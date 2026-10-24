package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.application.ports.UserRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralPhoto;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Application contract for MEM-006-R04, R10, R12 and AC02, AC03. */
class ListMuralPhotosTest {
    private static final Instant NOW = Instant.parse("2026-10-24T18:00:00Z");
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void signsOnlyPhotosAttachedToTheCurrentOwnersMural() {
        UUID muralId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        String storageKey = "originals/private/secret-key.png";
        Mural mural = Mural.create(muralId, OWNER_ID, "Family", null, NOW);
        MuralPhotoRepository murals = mock(MuralPhotoRepository.class);
        PhotoAssetRepository photos = mock(PhotoAssetRepository.class);
        PhotoStorage storage = mock(PhotoStorage.class);
        when(murals.findOwned(OWNER_ID, muralId)).thenReturn(new MuralPhotoSnapshot(
                mural, List.of(new MuralPhoto(muralId, photoId, 0))));
        when(photos.findByIdAndOwner(photoId, OWNER_ID.toString())).thenReturn(Optional.of(
                PhotoAsset.restore(photoId, OWNER_ID.toString(), storageKey, "image/png", 2048,
                        PhotoStatus.READY, NOW.minusSeconds(20), NOW.plusSeconds(900), 2, 3, NOW.minusSeconds(10), null)));
        when(storage.presignGet(storageKey, java.time.Duration.ofMinutes(5)))
                .thenReturn("http://minio:9000/private/key?X-Amz-Signature=secret");

        ListMuralPhotos useCase = new ListMuralPhotos(murals, photos, storage, currentUserId(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MuralPhotoViewCollection result = useCase.execute(muralId);

        assertThat(result.muralVersion()).isEqualTo(1);
        assertThat(result.items()).containsExactly(new MuralPhotoView(photoId, 0, "image/png", 2048,
                "http://minio:9000/private/key?X-Amz-Signature=secret", NOW.plusSeconds(300)));
        verify(murals).findOwned(OWNER_ID, muralId);
        verify(storage).presignGet(eq(storageKey), eq(java.time.Duration.ofMinutes(5)));
    }

    private static CurrentUserId currentUserId() {
        Identity identity = new Identity("urn:memora:test", "owner");
        CurrentIdentity currentIdentity = () -> identity;
        UserRepository users = ignored -> OWNER_ID;
        return new CurrentUserId(currentIdentity, users);
    }
}
