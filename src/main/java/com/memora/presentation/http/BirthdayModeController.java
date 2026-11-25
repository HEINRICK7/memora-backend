package com.memora.presentation.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.application.mural.GetOwnedMural;
import com.memora.application.mural.MuralQueryException;
import com.memora.config.PhotoLimitsProperties;
import com.memora.domain.mural.Mural;
import com.memora.infrastructure.persistence.BirthdayModeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public final class BirthdayModeController {
    private static final Pattern ETAG = Pattern.compile("^\"([1-9][0-9]*)\"$");
    private final BirthdayModeService birthday;
    private final GetOwnedMural getOwnedMural;
    private final UploadRateLimiter rateLimiter;
    private final int guestContributionsPerMinute;

    public BirthdayModeController(BirthdayModeService birthday, GetOwnedMural getOwnedMural,
                                  UploadRateLimiter rateLimiter, PhotoLimitsProperties limits) {
        this.birthday = birthday;
        this.getOwnedMural = getOwnedMural;
        this.rateLimiter = rateLimiter;
        this.guestContributionsPerMinute = limits.effectiveGuestContributionsPerMinute();
    }

    @PostMapping("/murals/{muralId}/publish")
    public ResponseEntity<MuralController.MuralSummary> publish(
            @PathVariable String muralId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String rawVersion,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey
    ) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        int version = parseVersion(rawVersion);
        UUID idempotencyKey = uuid(rawKey, "A chave de idempotência precisa ser um UUID.");
        birthday.publish(id, version, idempotencyKey);
        Mural mural = getOwnedMural.execute(id);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.ETAG, "\"" + mural.version() + "\"")
                .body(MuralController.MuralSummary.from(mural));
    }

    @PostMapping("/murals/{muralId}/grants")
    public ResponseEntity<BirthdayModeService.GrantView> createGrant(
            @PathVariable String muralId,
            @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
            @RequestBody JsonNode body
    ) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        UUID key = uuid(rawKey, "A chave de idempotência precisa ser um UUID.");
        GrantRequest request = parseGrantRequest(body);
        BirthdayModeService.GrantCreation created = birthday.createGrant(id, key, request.scope(), request.expiresAt());
        HttpStatus status = created.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return privateResponse(ResponseEntity.status(status)
                .header("Idempotency-Replayed", Boolean.toString(created.replayed())))
                .body(created.grant());
    }

    @GetMapping("/murals/{muralId}/grants")
    public ResponseEntity<GrantListResponse> listGrants(@PathVariable String muralId) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        return privateResponse(ResponseEntity.ok()).body(new GrantListResponse(birthday.listGrants(id), null));
    }

    @DeleteMapping("/murals/{muralId}/grants/{grantId}")
    public ResponseEntity<Void> revokeGrant(@PathVariable String muralId, @PathVariable String grantId) {
        birthday.revokeGrant(uuid(muralId, "O identificador do mural precisa ser um UUID."),
                uuid(grantId, "O identificador do link precisa ser um UUID."));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("Referrer-Policy", "no-referrer").build();
    }

    @GetMapping("/murals/{muralId}/qr-logo")
    public ResponseEntity<BirthdayModeService.QrLogoView> getQrLogo(@PathVariable String muralId) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        return privateResponse(ResponseEntity.ok()).body(birthday.getQrLogo(id));
    }

    @PostMapping("/murals/{muralId}/qr-logo")
    public ResponseEntity<BirthdayModeService.QrLogoView> saveQrLogo(
            @PathVariable String muralId, @RequestBody JsonNode body
    ) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        requireFields(body, Set.of("logoDataUrl"));
        JsonNode logo = body.get("logoDataUrl");
        if (logo == null || !logo.isTextual()) throw new MuralQueryException("O logo enviado é inválido.");
        return privateResponse(ResponseEntity.ok()).body(birthday.saveQrLogo(id, logo.textValue()));
    }

    @DeleteMapping("/murals/{muralId}/qr-logo")
    public ResponseEntity<Void> deleteQrLogo(@PathVariable String muralId) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        birthday.deleteQrLogo(id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    @GetMapping("/shared/{shareToken}")
    public ResponseEntity<BirthdayModeService.SharedMuralView> getShared(
            @PathVariable String shareToken,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken
    ) {
        return privateResponse(ResponseEntity.ok())
                .body(birthday.getSharedMural(shareToken, limit, offset, guestToken));
    }

    @PostMapping("/shared/{shareToken}/guest-album")
    public ResponseEntity<BirthdayModeService.GuestAlbumSession> openGuestAlbum(
            @PathVariable String shareToken, @RequestBody JsonNode body
    ) {
        GuestAlbumRequest request = parseGuestAlbumRequest(body);
        return privateResponse(ResponseEntity.ok())
                .body(birthday.openGuestAlbum(shareToken, request.guestToken(), request.displayName()));
    }

    @GetMapping("/shared/{shareToken}/guest-album")
    public ResponseEntity<ContributionListResponse> getGuestAlbum(
            @PathVariable String shareToken,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken
    ) {
        return privateResponse(ResponseEntity.ok())
                .body(new ContributionListResponse(birthday.getGuestAlbum(shareToken, guestToken)));
    }

    @PostMapping("/shared/{shareToken}/guest-album/contributions")
    public ResponseEntity<BirthdayModeService.UploadIntent> startContribution(
            @PathVariable String shareToken,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken,
            @RequestBody JsonNode body,
            HttpServletRequest httpRequest
    ) {
        String rateKey = "guest-contrib:" + BirthdayModeService.sha256(shareToken) + ":" + clientAddress(httpRequest);
        rateLimiter.checkOrThrow(rateKey, guestContributionsPerMinute);
        ContributionRequest request = parseContributionRequest(body);
        return privateResponse(ResponseEntity.status(HttpStatus.CREATED))
                .body(birthday.startContribution(
                        shareToken, guestToken, request.contentType(), request.sizeBytes(), request.displayName()));
    }

    @PostMapping("/shared/{shareToken}/guest-album/contributions/{contributionId}/complete")
    public ResponseEntity<BirthdayModeService.ContributionReceipt> completeContribution(
            @PathVariable String shareToken,
            @PathVariable String contributionId,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken,
            @RequestParam(defaultValue = "true") boolean autoPublish
    ) {
        return privateResponse(ResponseEntity.ok())
                .body(birthday.completeContribution(shareToken, guestToken,
                        uuid(contributionId, "O identificador da contribuição precisa ser um UUID."), autoPublish));
    }

    @DeleteMapping("/shared/{shareToken}/guest-album/photos/{photoId}")
    public ResponseEntity<Void> deleteGuestPhoto(
            @PathVariable String shareToken,
            @PathVariable String photoId,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken
    ) {
        birthday.deleteGuestPhoto(shareToken, guestToken,
                uuid(photoId, "O identificador da fotografia precisa ser um UUID."));
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @PostMapping("/shared/{shareToken}/guest-album/publish")
    public ResponseEntity<ContributionReceiptListResponse> publishGuestPhotos(
            @PathVariable String shareToken,
            @RequestHeader(value = "X-Guest-Album-Token", required = false) String guestToken,
            @RequestBody JsonNode body
    ) {
        List<UUID> contributionIds = parsePublishRequest(body);
        return privateResponse(ResponseEntity.ok()).body(new ContributionReceiptListResponse(
                birthday.publishGuestPhotos(shareToken, guestToken, contributionIds)));
    }

    @GetMapping("/murals/{muralId}/contributions")
    public ResponseEntity<ContributionListResponse> listContributions(@PathVariable String muralId) {
        UUID id = uuid(muralId, "O identificador do mural precisa ser um UUID.");
        return privateResponse(ResponseEntity.ok()).body(new ContributionListResponse(birthday.listContributions(id)));
    }

    private static <T> ResponseEntity.BodyBuilder privateResponse(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("Referrer-Policy", "no-referrer");
    }

    private static GrantRequest parseGrantRequest(JsonNode body) {
        requireFields(body, Set.of("scope", "expiresAt"));
        JsonNode scopeNode = body.get("scope");
        JsonNode expiryNode = body.get("expiresAt");
        if (scopeNode == null || !scopeNode.isTextual() || expiryNode == null) {
            throw new MuralQueryException("Os dados do link são inválidos.");
        }
        String scope = scopeNode.textValue();
        if (!"VIEW".equals(scope) && !"CONTRIBUTE".equals(scope)) {
            throw new BirthdayModeException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_grant_scope",
                    "O tipo de link não é aceito.");
        }
        Instant expiresAt = null;
        if (!expiryNode.isNull()) {
            if (!expiryNode.isTextual()) throw new MuralQueryException("A validade do link é inválida.");
            try {
                expiresAt = Instant.parse(expiryNode.textValue());
            } catch (RuntimeException malformed) {
                throw new MuralQueryException("A validade do link é inválida.");
            }
        }
        return new GrantRequest(scope, expiresAt);
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static ContributionRequest parseContributionRequest(JsonNode body) {
        requireFields(body, Set.of("contentType", "sizeBytes", "displayName"));
        JsonNode type = body.get("contentType");
        JsonNode size = body.get("sizeBytes");
        JsonNode name = body.get("displayName");
        if (type == null || !type.isTextual() || size == null || !size.canConvertToLong()
                || (name != null && !name.isNull() && !name.isTextual())) {
            throw new MuralQueryException("Os dados da fotografia são inválidos.");
        }
        return new ContributionRequest(type.textValue(), size.longValue(),
                name == null || name.isNull() ? null : name.textValue());
    }

    private static GuestAlbumRequest parseGuestAlbumRequest(JsonNode body) {
        requireFields(body, Set.of("guestToken", "displayName"));
        JsonNode token = body.get("guestToken");
        JsonNode name = body.get("displayName");
        if (token == null || !token.isTextual() || (name != null && !name.isNull() && !name.isTextual())) {
            throw new MuralQueryException("Os dados do álbum pessoal são inválidos.");
        }
        return new GuestAlbumRequest(token.textValue(), name == null || name.isNull() ? null : name.textValue());
    }

    private static List<UUID> parsePublishRequest(JsonNode body) {
        requireFields(body, Set.of("contributionIds"));
        JsonNode ids = body.get("contributionIds");
        if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 50) {
            throw new MuralQueryException("Escolha de 1 a 50 fotos para compartilhar.");
        }
        return java.util.stream.StreamSupport.stream(ids.spliterator(), false)
                .map(node -> {
                    if (!node.isTextual()) throw new MuralQueryException("A seleção de fotos é inválida.");
                    return uuid(node.textValue(), "A seleção de fotos é inválida.");
                }).toList();
    }

    private static void requireFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw new MuralQueryException("O corpo precisa ser um objeto JSON.");
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) throw new MuralQueryException("O corpo contém um campo não permitido.");
        }
    }

    private static int parseVersion(String raw) {
        if (raw == null) throw new MissingIfMatchException();
        Matcher matcher = ETAG.matcher(raw);
        if (!matcher.matches()) throw new InvalidIfMatchException();
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException malformed) {
            throw new InvalidIfMatchException();
        }
    }

    private static UUID uuid(String value, String message) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException malformed) {
            throw new MuralQueryException(message);
        }
    }

    private record GrantRequest(String scope, Instant expiresAt) { }
    private record ContributionRequest(String contentType, long sizeBytes, String displayName) { }
    private record GuestAlbumRequest(String guestToken, String displayName) { }
    public record GrantListResponse(List<BirthdayModeService.GrantView> items, String nextCursor) { }
    public record ContributionListResponse(List<BirthdayModeService.ContributionView> items) { }
    public record ContributionReceiptListResponse(List<BirthdayModeService.ContributionReceipt> items) { }
}
