package com.memora.presentation.http;

import com.memora.application.mural.GetOwnedMural;
import com.memora.config.PhotoLimitsProperties;
import com.memora.infrastructure.persistence.BirthdayModeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BirthdayDirectGuestFlowTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void supportsDirectGuestPublishAndOwnPhotoDeletion() throws Exception {
        BirthdayModeService service = mock(BirthdayModeService.class);
        GetOwnedMural getOwnedMural = mock(GetOwnedMural.class);
        UploadRateLimiter limiter = new UploadRateLimiter(CLOCK, Duration.ofMinutes(1));
        PhotoLimitsProperties limits = new PhotoLimitsProperties(500, 300, 200, 60, 30, 100);
        BirthdayModeController controller = new BirthdayModeController(service, getOwnedMural, limiter, limits);

        String shareToken = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
        String guestToken = "guest-token-1234567890abcdef";
        UUID contributionId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();

        BirthdayModeService.SharedPhoto samplePhoto = new BirthdayModeService.SharedPhoto(
                photoId, 0, "image/jpeg", 4096L, 1200, 1600,
                "https://s3.local/share.jpg", "https://s3.local/thumb.jpg",
                CLOCK.instant().plusSeconds(600), true, "Henrique"
        );
        BirthdayModeService.SharedMuralView sharedView = new BirthdayModeService.SharedMuralView(
                new BirthdayModeService.SharedMural("Aniversário", null, null, List.of(samplePhoto), null, null, 1, null),
                new BirthdayModeService.Permissions(true, true, false)
        );
        when(service.getSharedMural(shareToken, null, null, guestToken)).thenReturn(sharedView);

        ResponseEntity<BirthdayModeService.SharedMuralView> sharedResp =
                controller.getShared(shareToken, null, null, guestToken);
        assertThat(sharedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sharedResp.getBody().mural().photos().getFirst().authorName()).isEqualTo("Henrique");
        verify(service).getSharedMural(shareToken, null, null, guestToken);

        BirthdayModeService.UploadIntent intent = new BirthdayModeService.UploadIntent(
                contributionId, "PUT", "https://s3.local/put", Map.of("Content-Type", "image/jpeg"),
                CLOCK.instant().plusSeconds(300)
        );
        when(service.startContribution(shareToken, guestToken, "image/jpeg", 4096L, "Ana")).thenReturn(intent);

        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("127.0.0.1");
        ResponseEntity<BirthdayModeService.UploadIntent> startResp = controller.startContribution(
                shareToken,
                guestToken,
                MAPPER.readTree("""
                        {"contentType":"image/jpeg","sizeBytes":4096,"displayName":"Ana"}
                        """),
                httpReq
        );
        assertThat(startResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).startContribution(shareToken, guestToken, "image/jpeg", 4096L, "Ana");

        BirthdayModeService.ContributionReceipt receipt =
                new BirthdayModeService.ContributionReceipt(contributionId, photoId, "PUBLISHED");
        when(service.completeContribution(shareToken, guestToken, contributionId, true)).thenReturn(receipt);

        ResponseEntity<BirthdayModeService.ContributionReceipt> completeResp =
                controller.completeContribution(shareToken, contributionId.toString(), guestToken, true);
        assertThat(completeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completeResp.getBody()).isEqualTo(receipt);

        ResponseEntity<Void> deleteResp =
                controller.deleteGuestPhoto(shareToken, photoId.toString(), guestToken);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).deleteGuestPhoto(shareToken, guestToken, photoId);
    }
}
