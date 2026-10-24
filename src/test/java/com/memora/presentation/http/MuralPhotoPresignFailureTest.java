package com.memora.presentation.http;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.mural.AttachPhotoToMural;
import com.memora.application.mural.ListMuralPhotos;
import com.memora.application.mural.MuralPhotoSnapshot;
import com.memora.application.mural.RemovePhotoFromMural;
import com.memora.application.mural.ReorderMuralPhotos;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.application.ports.UserRepository;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralPhoto;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Clock;
import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** MEM-006-AC15: a failed presign must not return earlier signed items or leak failure details. */
class MuralPhotoPresignFailureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-24T18:00:00Z");
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void AC15_mapsSecondPhotoPresignFailureToSanitized503WithoutPartialCollection() throws Exception {
        UUID muralId = UUID.randomUUID();
        UUID firstPhotoId = UUID.randomUUID();
        UUID secondPhotoId = UUID.randomUUID();
        String firstKey = "originals/private/first-secret.png";
        String secondKey = "originals/private/second-secret.png";
        String firstSignedUrl = "http://minio:9000/private/first?X-Amz-Signature=already-created-secret";
        String presignFailureDetail = "X-Amz-Signature=failed-secret " + secondKey;

        MuralPhotoRepository murals = mock(MuralPhotoRepository.class);
        PhotoAssetRepository photos = mock(PhotoAssetRepository.class);
        PhotoStorage storage = mock(PhotoStorage.class);
        Mural mural = Mural.create(muralId, OWNER_ID, "Private", null, NOW);
        when(murals.findOwned(OWNER_ID, muralId)).thenReturn(new MuralPhotoSnapshot(mural, List.of(
                new MuralPhoto(muralId, firstPhotoId, 0), new MuralPhoto(muralId, secondPhotoId, 1))));
        when(photos.findByIdAndOwner(firstPhotoId, OWNER_ID.toString())).thenReturn(Optional.of(readyPhoto(firstPhotoId, firstKey)));
        when(photos.findByIdAndOwner(secondPhotoId, OWNER_ID.toString())).thenReturn(Optional.of(readyPhoto(secondPhotoId, secondKey)));
        when(storage.presignGet(firstKey, Duration.ofMinutes(5))).thenReturn(firstSignedUrl);
        when(storage.presignGet(secondKey, Duration.ofMinutes(5)))
                .thenThrow(SdkClientException.create(presignFailureDetail));

        ListMuralPhotos list = new ListMuralPhotos(murals, photos, storage, currentUserId(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MuralPhotoController controller = new MuralPhotoController(list,
                mock(AttachPhotoToMural.class), mock(ReorderMuralPhotos.class), mock(RemovePhotoFromMural.class));
        MockMvc http = standaloneSetup(controller).setControllerAdvice(new ApiProblemHandler()).build();

        var response = http.perform(get("/api/v1/murals/{muralId}/photos", muralId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse();

        String responseBody = response.getContentAsString();
        JsonNode problem = JSON.readTree(responseBody);
        assertThat(problem.has("items")).isFalse();
        assertThat(problem.has("previewUrl")).isFalse();
        assertThat(responseBody)
                .contains("dependency_unavailable")
                .doesNotContain(firstSignedUrl, "X-Amz-Signature", "already-created-secret", "failed-secret", secondKey);
        verify(storage).presignGet(eq(firstKey), any(Duration.class));
        verify(storage).presignGet(eq(secondKey), any(Duration.class));
    }

    private static PhotoAsset readyPhoto(UUID id, String storageKey) {
        return PhotoAsset.restore(id, OWNER_ID.toString(), storageKey, "image/png", 2048,
                PhotoStatus.READY, NOW.minusSeconds(20), NOW.plusSeconds(900), 2, 3, NOW.minusSeconds(10), null);
    }

    private static CurrentUserId currentUserId() {
        Identity identity = new Identity("urn:memora:test", "owner");
        CurrentIdentity currentIdentity = () -> identity;
        UserRepository users = ignored -> OWNER_ID;
        return new CurrentUserId(currentIdentity, users);
    }
}
