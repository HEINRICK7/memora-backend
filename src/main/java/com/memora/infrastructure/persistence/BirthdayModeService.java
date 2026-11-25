package com.memora.infrastructure.persistence;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.mural.MuralIdempotencyConflictException;
import com.memora.application.mural.MuralNotFoundException;
import com.memora.application.mural.MuralStaleVersionException;
import com.memora.application.ports.PhotoStorage;
import com.memora.config.PhotoLimitsProperties;
import com.memora.domain.mural.MuralPhotoLimitExceededException;
import com.memora.domain.photo.PhotoValidationException;
import com.memora.domain.photo.ProcessedImage;
import com.memora.domain.photo.UploadMetadata;
import com.memora.presentation.http.BirthdayModeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BirthdayModeService {
    public static final Duration PUBLIC_MEDIA_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_SHARED_PAGE_LIMIT = 60;
    public static final int MAX_SHARED_PAGE_LIMIT = 120;
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    private static final Duration UPLOAD_INTENT_TTL = Duration.ofMinutes(15);
    private static final String QR_LOGO_PREFIX = "data:image/png;base64,";
    private static final int MAX_QR_LOGO_BYTES = 512 * 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CurrentUserId currentUser;
    private final PhotoStorage storage;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String publicBaseUrl;
    private final long maxUploadBytes;
    private final int maxPhotosPerMural;
    private final int maxContributionsPerGrant;

    public BirthdayModeService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            CurrentUserId currentUser,
            PhotoStorage storage,
            Clock clock,
            PhotoLimitsProperties limits,
            @Value("${memora.public-base-url:http://localhost:3000}") String publicBaseUrl,
            @Value("${memora.storage.max-upload-bytes:20971520}") long maxUploadBytes
    ) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.currentUser = currentUser;
        this.storage = storage;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.maxUploadBytes = maxUploadBytes;
        this.maxPhotosPerMural = limits.effectiveMaxPhotosPerMural();
        this.maxContributionsPerGrant = limits.effectiveMaxContributionsPerGrant();
    }

    /**
     * Publishes a mural without holding any JDBC transaction or FOR UPDATE lock during S3/ImageIO work.
     */
    public void publish(UUID muralId, int expectedVersion, UUID idempotencyKey) {
        UUID ownerId = currentUser.get();
        String requestHash = sha256("birthday-publish:" + muralId + ":" + expectedVersion);

        // Phase 1 (short TX): validate ownership, idempotency, state, and list photos lacking derivatives.
        PublishPrep prep = tx.execute(status -> {
            List<PublicationState> rows = jdbc.query("""
                    SELECT status, lock_version, publish_idempotency_key, publish_request_hash
                    FROM murals WHERE id = ? AND owner_id = ?
                    """, (rs, row) -> new PublicationState(
                    rs.getString("status"), rs.getLong("lock_version"),
                    rs.getObject("publish_idempotency_key", UUID.class), rs.getString("publish_request_hash")),
                    muralId, ownerId);
            if (rows.isEmpty()) throw new MuralNotFoundException();
            PublicationState state = rows.getFirst();
            if (state.idempotencyKey() != null) {
                if (state.idempotencyKey().equals(idempotencyKey)) {
                    if (!requestHash.equals(state.requestHash())) throw new MuralIdempotencyConflictException();
                    return new PublishPrep(true, List.of());
                }
                throw new MuralStaleVersionException();
            }
            if (!"DRAFT".equals(state.status()) || state.lockVersion() + 1 != expectedVersion) {
                throw new MuralStaleVersionException();
            }
            List<ShareViewSource> pendingSources = jdbc.query("""
                    SELECT pa.id, pa.storage_key, pa.content_type, pa.size_bytes
                    FROM mural_photos mp JOIN photo_assets pa ON pa.id = mp.photo_id
                    WHERE mp.mural_id = ? AND pa.status = 'READY'
                      AND (pa.share_view_status <> 'READY' OR pa.thumbnail_storage_key IS NULL)
                    ORDER BY mp.position ASC
                    """, (rs, row) -> new ShareViewSource(rs.getObject("id", UUID.class),
                    rs.getString("storage_key"), rs.getString("content_type"), rs.getLong("size_bytes")), muralId);
            return new PublishPrep(false, pendingSources);
        });
        if (prep == null || prep.alreadyPublished()) {
            return;
        }

        // Phase 2 (NO DB TX / NO DB LOCK): process missing derivatives in single-pass S3+ImageProcessor.
        for (ShareViewSource source : prep.pendingSources()) {
            String shareViewKey = shareViewKeyFor(source.photoId(), source.contentType());
            String thumbnailKey = thumbnailKeyFor(source.photoId());
            ProcessedImage processed = storage.processUploadedPhoto(
                    source.storageKey(), shareViewKey, thumbnailKey, source.sizeBytes(), source.contentType()
            );
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE photo_assets SET share_view_status = 'READY', share_view_storage_key = ?,
                        share_view_content_type = ?, share_view_size_bytes = ?, share_view_width = ?,
                        share_view_height = ?, share_view_processor_version = 'memora-share-view-v2',
                        share_view_ready_at = ?, thumbnail_storage_key = ?, thumbnail_width = ?,
                        thumbnail_height = ?, thumbnail_size_bytes = ?
                    WHERE id = ?
                    """, shareViewKey, processed.shareView().contentType(), processed.shareView().sizeBytes(),
                    processed.shareView().width(), processed.shareView().height(), timestamp(clock.instant()),
                    thumbnailKey, processed.thumbnail().width(), processed.thumbnail().height(),
                    processed.thumbnail().sizeBytes(), source.photoId()));
        }

        // Phase 3 (short TX): lock mural row, re-verify state/invariants, and mark PUBLISHED.
        tx.executeWithoutResult(status -> {
            List<PublicationState> rows = jdbc.query("""
                    SELECT status, lock_version, publish_idempotency_key, publish_request_hash
                    FROM murals WHERE id = ? AND owner_id = ? FOR UPDATE
                    """, (rs, row) -> new PublicationState(
                    rs.getString("status"), rs.getLong("lock_version"),
                    rs.getObject("publish_idempotency_key", UUID.class), rs.getString("publish_request_hash")),
                    muralId, ownerId);
            if (rows.isEmpty()) throw new MuralNotFoundException();
            PublicationState state = rows.getFirst();
            if (state.idempotencyKey() != null) {
                if (state.idempotencyKey().equals(idempotencyKey)) {
                    if (!requestHash.equals(state.requestHash())) throw new MuralIdempotencyConflictException();
                    return;
                }
                throw new MuralStaleVersionException();
            }
            if (!"DRAFT".equals(state.status()) || state.lockVersion() + 1 != expectedVersion) {
                throw new MuralStaleVersionException();
            }
            Integer invalidPhotos = jdbc.queryForObject("""
                    SELECT count(*) FROM mural_photos mp
                    JOIN photo_assets pa ON pa.id = mp.photo_id
                    WHERE mp.mural_id = ? AND (pa.status <> 'READY' OR pa.owner_id <> ?
                        OR pa.share_view_status <> 'READY')
                    """, Integer.class, muralId, ownerId);
            if (invalidPhotos != null && invalidPhotos > 0) {
                throw new BirthdayModeException(HttpStatus.CONFLICT, "mural_not_publishable",
                        "Uma fotografia da festa ainda não está pronta.");
            }
            try {
                Instant now = clock.instant();
                jdbc.update("""
                        UPDATE murals
                        SET status = 'PUBLISHED', published_at = ?, publish_idempotency_key = ?,
                            publish_request_hash = ?, lock_version = lock_version + 1, updated_at = ?
                        WHERE id = ? AND owner_id = ?
                        """, timestamp(now), idempotencyKey, requestHash, timestamp(now), muralId, ownerId);
            } catch (DataIntegrityViolationException duplicateKey) {
                throw new MuralIdempotencyConflictException();
            }
        });
    }

    @Transactional
    public GrantCreation createGrant(UUID muralId, UUID idempotencyKey, String scope, Instant expiresAt) {
        UUID ownerId = currentUser.get();
        ensureOwnedPublishedMural(ownerId, muralId);
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new BirthdayModeException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_grant_expiration",
                    "A validade do link precisa estar no futuro.");
        }
        String requestHash = sha256(scope + "|" + (expiresAt == null ? "never" : expiresAt.toString()));
        List<GrantRow> existing = jdbc.query("""
                SELECT id, scope, created_at, expires_at, revoked_at, request_hash
                FROM share_grants WHERE mural_id = ? AND idempotency_key = ?
                """, (rs, row) -> new GrantRow(
                rs.getObject("id", UUID.class), rs.getString("scope"), rs.getTimestamp("created_at").toInstant(),
                instantOrNull(rs.getTimestamp("expires_at")), instantOrNull(rs.getTimestamp("revoked_at")),
                rs.getString("request_hash")), muralId, idempotencyKey);
        if (!existing.isEmpty()) {
            GrantRow row = existing.getFirst();
            if (!requestHash.equals(row.requestHash())) throw new MuralIdempotencyConflictException();
            return new GrantCreation(toGrant(row, null, false), true);
        }

        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        UUID grantId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            jdbc.update("""
                    INSERT INTO share_grants
                    (id, mural_id, token_hash, scope, idempotency_key, request_hash, expires_at, revoked_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
                    """, grantId, muralId, sha256(token), scope, idempotencyKey, requestHash,
                    timestamp(expiresAt), timestamp(now));
        } catch (DataIntegrityViolationException duplicateKey) {
            throw new MuralIdempotencyConflictException();
        }
        GrantRow row = new GrantRow(grantId, scope, now, expiresAt, null, requestHash);
        return new GrantCreation(toGrant(row, publicBaseUrl + "/m/" + token, true), false);
    }

    @Transactional
    public List<GrantView> listGrants(UUID muralId) {
        UUID ownerId = currentUser.get();
        ensureOwnedMural(ownerId, muralId);
        return jdbc.query("""
                SELECT id, scope, created_at, expires_at, revoked_at
                FROM share_grants WHERE mural_id = ? ORDER BY created_at DESC, id DESC
                """, (rs, row) -> toGrant(new GrantRow(
                rs.getObject("id", UUID.class), rs.getString("scope"), rs.getTimestamp("created_at").toInstant(),
                instantOrNull(rs.getTimestamp("expires_at")), instantOrNull(rs.getTimestamp("revoked_at")), null),
                null, false), muralId);
    }

    @Transactional
    public void revokeGrant(UUID muralId, UUID grantId) {
        UUID ownerId = currentUser.get();
        ensureOwnedMural(ownerId, muralId);
        int updated = jdbc.update("""
                UPDATE share_grants g SET revoked_at = COALESCE(g.revoked_at, ?)
                FROM murals m WHERE g.id = ? AND g.mural_id = ? AND m.id = g.mural_id AND m.owner_id = ?
                """, timestamp(clock.instant()), grantId, muralId, ownerId);
        if (updated == 0) throw new MuralNotFoundException();
    }

    @Transactional(readOnly = true)
    public QrLogoView getQrLogo(UUID muralId) {
        UUID ownerId = currentUser.get();
        ensureOwnedMural(ownerId, muralId);
        List<String> keys = jdbc.query("SELECT qr_logo_storage_key FROM murals WHERE id = ?",
                (rs, row) -> rs.getString(1), muralId);
        String key = keys.isEmpty() ? null : keys.getFirst();
        if (key == null) return new QrLogoView(null, null);
        Instant expiresAt = clock.instant().plus(PUBLIC_MEDIA_TTL);
        return new QrLogoView(storage.presignGet(key, PUBLIC_MEDIA_TTL, PhotoStorage.PRIVATE_DERIVED_CACHE), expiresAt);
    }

    public QrLogoView saveQrLogo(UUID muralId, String dataUrl) {
        UUID ownerId = currentUser.get();
        byte[] png = decodeQrLogo(dataUrl);
        String key = "derived/private/qr-logo/v1/" + muralId + ".png";
        tx.executeWithoutResult(status -> ensureOwnedMural(ownerId, muralId));
        storage.putPrivateObject(key, "image/png", png);
        tx.executeWithoutResult(status -> {
            ensureOwnedMural(ownerId, muralId);
            jdbc.update("UPDATE murals SET qr_logo_storage_key = ? WHERE id = ? AND owner_id = ?",
                    key, muralId, ownerId);
        });
        Instant expiresAt = clock.instant().plus(PUBLIC_MEDIA_TTL);
        return new QrLogoView(storage.presignGet(key, PUBLIC_MEDIA_TTL, PhotoStorage.PRIVATE_DERIVED_CACHE), expiresAt);
    }

    @Transactional
    public void deleteQrLogo(UUID muralId) {
        UUID ownerId = currentUser.get();
        ensureOwnedMural(ownerId, muralId);
        List<String> keys = jdbc.query("SELECT qr_logo_storage_key FROM murals WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), muralId);
        String key = keys.isEmpty() ? null : keys.getFirst();
        jdbc.update("UPDATE murals SET qr_logo_storage_key = NULL WHERE id = ? AND owner_id = ?", muralId, ownerId);
        if (key != null) storage.delete(key);
    }

    @Transactional(readOnly = true)
    public SharedMuralView getSharedMural(String token) {
        return getSharedMural(token, null, null, null);
    }

    @Transactional(readOnly = true)
    public SharedMuralView getSharedMural(String token, Integer requestedLimit, Integer requestedOffset) {
        return getSharedMural(token, requestedLimit, requestedOffset, null);
    }

    @Transactional(readOnly = true)
    public SharedMuralView getSharedMural(String token, Integer requestedLimit, Integer requestedOffset, String guestToken) {
        GrantContext context = resolveGrant(token, false);
        int limit = requestedLimit == null
                ? DEFAULT_SHARED_PAGE_LIMIT
                : Math.max(1, Math.min(requestedLimit, MAX_SHARED_PAGE_LIMIT));
        int offset = requestedOffset == null ? 0 : Math.max(0, requestedOffset);

        UUID guestAlbumId = findOptionalGuestAlbumId(context, guestToken);

        Integer totalCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM mural_photos mp JOIN photo_assets pa ON pa.id = mp.photo_id
                WHERE mp.mural_id = ? AND pa.status = 'READY' AND pa.share_view_status = 'READY'
                """, Integer.class, context.muralId());
        int totalPhotos = totalCount == null ? 0 : totalCount;

        Instant expiresAt = clock.instant().plus(PUBLIC_MEDIA_TTL);
        List<SharedPhoto> photos = jdbc.query("""
                SELECT pa.id, mp.position, pa.share_view_content_type AS content_type,
                       pa.share_view_size_bytes AS size_bytes, pa.share_view_width AS width,
                       pa.share_view_height AS height, pa.share_view_storage_key AS storage_key,
                       pa.thumbnail_storage_key AS thumb_key, c.guest_album_id,
                       COALESCE(NULLIF(BTRIM(c.display_name), ''), NULLIF(BTRIM(ga.display_name), '')) AS author_name
                FROM mural_photos mp
                JOIN photo_assets pa ON pa.id = mp.photo_id
                LEFT JOIN contributions c ON c.photo_id = pa.id AND c.mural_id = mp.mural_id
                LEFT JOIN guest_albums ga ON ga.id = c.guest_album_id
                WHERE mp.mural_id = ? AND pa.status = 'READY' AND pa.share_view_status = 'READY'
                ORDER BY mp.position ASC
                LIMIT ? OFFSET ?
                """, (rs, row) -> {
            String shareKey = rs.getString("storage_key");
            String thumbKey = rs.getString("thumb_key");
            String url = storage.presignGet(shareKey, PUBLIC_MEDIA_TTL, PhotoStorage.PRIVATE_DERIVED_CACHE);
            String thumbnailUrl = (thumbKey != null && !thumbKey.isBlank())
                    ? storage.presignGet(thumbKey, PUBLIC_MEDIA_TTL, PhotoStorage.PRIVATE_DERIVED_CACHE)
                    : url;
            UUID photoGuestAlbumId = rs.getObject("guest_album_id", UUID.class);
            boolean ownedByGuest = guestAlbumId != null && guestAlbumId.equals(photoGuestAlbumId);
            String authorName = rs.getString("author_name");
            return new SharedPhoto(rs.getObject("id", UUID.class), rs.getInt("position"),
                    rs.getString("content_type"), rs.getLong("size_bytes"), rs.getInt("width"),
                    rs.getInt("height"), url, thumbnailUrl, expiresAt, ownedByGuest, authorName);
        }, context.muralId(), limit, offset);

        Integer nextOffset = (offset + photos.size() < totalPhotos) ? offset + photos.size() : null;
        String logoUrl = context.logoStorageKey() == null ? null
                : storage.presignGet(context.logoStorageKey(), PUBLIC_MEDIA_TTL, PhotoStorage.PRIVATE_DERIVED_CACHE);
        Instant logoExpiresAt = logoUrl == null ? null : expiresAt;
        return new SharedMuralView(
                new SharedMural(context.title(), context.description(), context.coverPhotoId(), photos,
                        logoUrl, logoExpiresAt, totalPhotos, nextOffset),
                new Permissions(true, "CONTRIBUTE".equals(context.scope()), false));
    }

    @Transactional
    public GuestAlbumSession openGuestAlbum(String shareToken, String guestToken, String displayName) {
        GrantContext grant = resolveGrant(shareToken, true);
        String tokenHash = hashGuestToken(guestToken);
        String normalizedName = normalizeDisplayName(displayName);
        List<GuestAlbumRow> existing = jdbc.query("""
                SELECT id, mural_id, grant_id, display_name FROM guest_albums WHERE token_hash = ?
                """, (rs, row) -> new GuestAlbumRow(
                rs.getObject("id", UUID.class),
                rs.getObject("mural_id", UUID.class),
                rs.getObject("grant_id", UUID.class),
                rs.getString("display_name")), tokenHash);
        if (!existing.isEmpty()) {
            GuestAlbumRow album = existing.getFirst();
            if (!album.grantId().equals(grant.grantId()) || !album.muralId().equals(grant.muralId())) {
                throw new BirthdayModeException(HttpStatus.FORBIDDEN, "guest_album_forbidden",
                        "Este álbum pessoal pertence a outra festa.");
            }
            if (normalizedName != null && !normalizedName.equals(album.displayName())) {
                jdbc.update("UPDATE guest_albums SET display_name = ? WHERE id = ?", normalizedName, album.id());
                return new GuestAlbumSession(normalizedName);
            }
            return new GuestAlbumSession(album.displayName());
        }
        try {
            jdbc.update("""
                    INSERT INTO guest_albums (id, mural_id, grant_id, token_hash, display_name, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), grant.muralId(), grant.grantId(), tokenHash, normalizedName,
                    timestamp(clock.instant()));
        } catch (DataIntegrityViolationException race) {
            return openGuestAlbum(shareToken, guestToken, displayName);
        }
        return new GuestAlbumSession(normalizedName);
    }

    @Transactional(readOnly = true)
    public List<ContributionView> getGuestAlbum(String shareToken, String guestToken) {
        GrantContext grant = resolveGrant(shareToken, true);
        GuestAlbumRow album = resolveGuestAlbum(grant, guestToken);
        Instant expiresAt = clock.instant().plus(PUBLIC_MEDIA_TTL);
        return jdbc.query("""
                SELECT c.id, c.status, c.display_name, c.created_at,
                       COALESCE(pa.thumbnail_storage_key, pa.share_view_storage_key, pa.storage_key) AS preview_key
                FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                WHERE c.guest_album_id = ? AND c.mural_id = ?
                    AND c.status IN ('READY', 'PENDING', 'PUBLISHED', 'APPROVED')
                    AND pa.status = 'READY' AND pa.share_view_status = 'READY'
                ORDER BY c.created_at ASC, c.id ASC
                """, (rs, row) -> {
            String key = rs.getString("preview_key");
            String cache = key.startsWith("derived/") ? PhotoStorage.PRIVATE_DERIVED_CACHE : PhotoStorage.PRIVATE_NO_STORE;
            return new ContributionView(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("display_name"),
                    rs.getTimestamp("created_at").toInstant(),
                    storage.presignGet(key, PUBLIC_MEDIA_TTL, cache),
                    expiresAt
            );
        }, album.id(), grant.muralId());
    }

    @Transactional
    public UploadIntent startContribution(String shareToken, String guestToken, String contentType, long sizeBytes) {
        return startContribution(shareToken, guestToken, contentType, sizeBytes, null);
    }

    @Transactional
    public UploadIntent startContribution(
            String shareToken, String guestToken, String contentType, long sizeBytes, String displayName
    ) {
        GrantContext grant = resolveGrant(shareToken, true);
        GuestAlbumRow album = ensureGuestAlbum(grant, guestToken, displayName);
        UploadMetadata metadata = UploadMetadata.request(contentType, sizeBytes, maxUploadBytes);

        enforceContributionQuotas(grant);

        UUID photoId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();
        Instant now = clock.instant();
        String storageKey = "originals/private/" + photoId;
        String uploadUrl = storage.presignPut(storageKey, metadata.contentType(), metadata.sizeBytes(), UPLOAD_URL_TTL);
        jdbc.update("""
                INSERT INTO photo_assets
                (id, owner_id, storage_key, content_type, size_bytes, status, created_at, intent_expires_at,
                 width, height, ready_at, deleted_at, version, share_view_status)
                VALUES (?, ?, ?, ?, ?, 'UPLOADING', ?, ?, NULL, NULL, NULL, NULL, 0, 'ABSENT')
                """, photoId, grant.ownerId(), storageKey, metadata.contentType(), metadata.sizeBytes(),
                timestamp(now), timestamp(now.plus(UPLOAD_INTENT_TTL)));
        jdbc.update("""
                INSERT INTO contributions (id, mural_id, grant_id, photo_id, guest_album_id, status, display_name, created_at)
                VALUES (?, ?, ?, ?, ?, 'UPLOADING', ?, ?)
                """, contributionId, grant.muralId(), grant.grantId(), photoId, album.id(), album.displayName(),
                timestamp(now));
        return new UploadIntent(contributionId, "PUT", uploadUrl,
                Map.of("Content-Type", metadata.contentType()), now.plus(UPLOAD_URL_TTL));
    }

    public ContributionReceipt completeContribution(String shareToken, String guestToken, UUID contributionId) {
        return completeContribution(shareToken, guestToken, contributionId, true);
    }

    /**
     * Completes a guest contribution in 3 phases so no DB transaction or row lock is held during S3/ImageIO work.
     * When autoPublish is true (default direct party flow), publishes directly to mural_photos in Phase 3.
     */
    public ContributionReceipt completeContribution(
            String shareToken, String guestToken, UUID contributionId, boolean autoPublish
    ) {
        // Phase 1 (short TX): validate grant, guest album, and contribution intent state.
        GuestContributionContext context = tx.execute(status -> {
            GrantContext grant = resolveGrant(shareToken, true);
            GuestAlbumRow album = resolveGuestAlbum(grant, guestToken);
            List<UploadRow> rows = jdbc.query("""
                    SELECT c.status, c.photo_id, pa.storage_key, pa.content_type, pa.size_bytes,
                           pa.intent_expires_at, pa.status AS photo_status
                    FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                    WHERE c.id = ? AND c.grant_id = ? AND c.mural_id = ? AND c.guest_album_id = ?
                    """, (rs, row) -> new UploadRow(rs.getString("status"), rs.getObject("photo_id", UUID.class),
                    rs.getString("storage_key"), rs.getString("content_type"), rs.getLong("size_bytes"),
                    rs.getTimestamp("intent_expires_at").toInstant(), rs.getString("photo_status")),
                    contributionId, grant.grantId(), grant.muralId(), album.id());
            if (rows.isEmpty()) throw new MuralNotFoundException();
            return new GuestContributionContext(grant, album, rows.getFirst());
        });
        if (context == null) throw new MuralNotFoundException();

        UploadRow upload = context.upload();
        if ("APPROVED".equals(upload.contributionStatus()) || "PUBLISHED".equals(upload.contributionStatus())
                || (!autoPublish && "READY".equals(upload.contributionStatus()))) {
            return new ContributionReceipt(contributionId, upload.photoId(), upload.contributionStatus());
        }
        if (!"UPLOADING".equals(upload.contributionStatus()) || !"UPLOADING".equals(upload.photoStatus())) {
            throw new BirthdayModeException(HttpStatus.CONFLICT, "contribution_conflict",
                    "Esta contribuição não pode ser concluída neste estado.");
        }
        if (!clock.instant().isBefore(upload.intentExpiresAt())) {
            failContributionAndDeleteObject(contributionId, upload);
            throw new BirthdayModeException(HttpStatus.CONFLICT, "upload_expired", "O envio expirou. Tente novamente.");
        }

        // Phase 2 (NO DB TX / NO DB LOCK): single-pass S3 download + EXIF normalize + share-view + thumbnail.
        String shareViewKey = shareViewKeyFor(upload.photoId(), upload.contentType());
        String thumbnailKey = thumbnailKeyFor(upload.photoId());
        ProcessedImage processed;
        try {
            processed = storage.processUploadedPhoto(
                    upload.storageKey(), shareViewKey, thumbnailKey, upload.sizeBytes(), upload.contentType()
            );
        } catch (PhotoValidationException invalid) {
            failContributionAndDeleteObject(contributionId, upload);
            throw invalid;
        }

        // Phase 3 (short TX): lock contribution row, persist READY + derivatives, and optionally publish directly.
        return tx.execute(status -> {
            GrantContext grant = resolveGrant(shareToken, true);
            GuestAlbumRow album = resolveGuestAlbum(grant, guestToken);
            List<UploadRow> lockedRows = jdbc.query("""
                    SELECT c.status, c.photo_id, pa.storage_key, pa.content_type, pa.size_bytes,
                           pa.intent_expires_at, pa.status AS photo_status
                    FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                    WHERE c.id = ? AND c.grant_id = ? AND c.mural_id = ? AND c.guest_album_id = ? FOR UPDATE
                    """, (rs, row) -> new UploadRow(rs.getString("status"), rs.getObject("photo_id", UUID.class),
                    rs.getString("storage_key"), rs.getString("content_type"), rs.getLong("size_bytes"),
                    rs.getTimestamp("intent_expires_at").toInstant(), rs.getString("photo_status")),
                    contributionId, grant.grantId(), grant.muralId(), album.id());
            if (lockedRows.isEmpty()) throw new MuralNotFoundException();
            UploadRow locked = lockedRows.getFirst();
            if ("APPROVED".equals(locked.contributionStatus()) || "PUBLISHED".equals(locked.contributionStatus())
                    || (!autoPublish && "READY".equals(locked.contributionStatus()))) {
                return new ContributionReceipt(contributionId, locked.photoId(), locked.contributionStatus());
            }
            if (!"UPLOADING".equals(locked.contributionStatus()) || !"UPLOADING".equals(locked.photoStatus())) {
                throw new BirthdayModeException(HttpStatus.CONFLICT, "contribution_conflict",
                        "Esta contribuição não pode ser concluída neste estado.");
            }
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE photo_assets SET status = 'READY', width = ?, height = ?, ready_at = ?,
                        share_view_status = 'READY', share_view_storage_key = ?, share_view_content_type = ?,
                        share_view_size_bytes = ?, share_view_width = ?, share_view_height = ?,
                        share_view_processor_version = 'memora-share-view-v2', share_view_ready_at = ?,
                        thumbnail_storage_key = ?, thumbnail_width = ?, thumbnail_height = ?,
                        thumbnail_size_bytes = ?, version = version + 1
                    WHERE id = ?
                    """, processed.original().width(), processed.original().height(), timestamp(now),
                    shareViewKey, processed.shareView().contentType(), processed.shareView().sizeBytes(),
                    processed.shareView().width(), processed.shareView().height(), timestamp(now),
                    thumbnailKey, processed.thumbnail().width(), processed.thumbnail().height(),
                    processed.thumbnail().sizeBytes(), locked.photoId());

            if (!autoPublish) {
                jdbc.update("UPDATE contributions SET status = 'READY' WHERE id = ? AND status = 'UPLOADING'",
                        contributionId);
                return new ContributionReceipt(contributionId, locked.photoId(), "READY");
            }

            jdbc.queryForObject("SELECT id FROM murals WHERE id = ? FOR UPDATE", UUID.class, grant.muralId());
            Integer muralCount = jdbc.queryForObject(
                    "SELECT count(*) FROM mural_photos WHERE mural_id = ?", Integer.class, grant.muralId());
            if (muralCount != null && muralCount >= maxPhotosPerMural) {
                throw new MuralPhotoLimitExceededException(maxPhotosPerMural);
            }
            Integer nextPosition = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(position) + 1, 0) FROM mural_photos WHERE mural_id = ?",
                    Integer.class, grant.muralId());
            jdbc.update("INSERT INTO mural_photos (id, mural_id, photo_id, position) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), grant.muralId(), locked.photoId(), nextPosition == null ? 0 : nextPosition);
            jdbc.update("""
                    UPDATE murals SET cover_photo_id = COALESCE(cover_photo_id, ?),
                        lock_version = lock_version + 1, updated_at = ? WHERE id = ?
                    """, locked.photoId(), timestamp(now), grant.muralId());
            jdbc.update("""
                    UPDATE contributions SET status = 'PUBLISHED', decided_at = ?, decided_by = NULL
                    WHERE id = ? AND status = 'UPLOADING'
                    """, timestamp(now), contributionId);
            return new ContributionReceipt(contributionId, locked.photoId(), "PUBLISHED");
        });
    }

    /**
     * Deletes a photo from the shared mural ONLY if it was uploaded by the caller's guest album token.
     */
    public void deleteGuestPhoto(String shareToken, String guestToken, UUID photoId) {
        DeletedStorageKeys keysToDelete = tx.execute(status -> {
            GrantContext grant = resolveGrant(shareToken, true);
            GuestAlbumRow album = resolveGuestAlbum(grant, guestToken);

            // Lock mural row to keep cover_photo_id and positions consistent.
            jdbc.queryForObject("SELECT id FROM murals WHERE id = ? FOR UPDATE", UUID.class, grant.muralId());

            List<GuestOwnedPhoto> owned = jdbc.query("""
                    SELECT c.id AS contribution_id, pa.storage_key, pa.share_view_storage_key, pa.thumbnail_storage_key
                    FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                    WHERE c.photo_id = ? AND c.guest_album_id = ? AND c.mural_id = ? FOR UPDATE
                    """, (rs, row) -> new GuestOwnedPhoto(
                    rs.getObject("contribution_id", UUID.class),
                    rs.getString("storage_key"),
                    rs.getString("share_view_storage_key"),
                    rs.getString("thumbnail_storage_key")), photoId, album.id(), grant.muralId());
            if (owned.isEmpty()) {
                throw new MuralNotFoundException();
            }
            GuestOwnedPhoto target = owned.getFirst();

            // Reassign cover photo before removing from mural_photos (fk_murals_cover).
            UUID replacementCover = jdbc.query("""
                    SELECT photo_id FROM mural_photos
                    WHERE mural_id = ? AND photo_id <> ?
                    ORDER BY position ASC LIMIT 1
                    """, (rs, row) -> rs.getObject("photo_id", UUID.class), grant.muralId(), photoId)
                    .stream().findFirst().orElse(null);

            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE murals
                    SET cover_photo_id = CASE WHEN cover_photo_id = ? THEN ? ELSE cover_photo_id END,
                        lock_version = lock_version + 1,
                        updated_at = ?
                    WHERE id = ?
                    """, photoId, replacementCover, timestamp(now), grant.muralId());

            jdbc.update("DELETE FROM mural_photos WHERE mural_id = ? AND photo_id = ?", grant.muralId(), photoId);

            // Reindex remaining positions contiguously (0..N-1).
            List<UUID> remainingIds = jdbc.query(
                    "SELECT id FROM mural_photos WHERE mural_id = ? ORDER BY position ASC",
                    (rs, row) -> rs.getObject("id", UUID.class), grant.muralId());
            for (int i = 0; i < remainingIds.size(); i++) {
                jdbc.update("UPDATE mural_photos SET position = ? WHERE id = ?", i, remainingIds.get(i));
            }

            jdbc.update("""
                    UPDATE contributions SET status = 'FAILED', decided_at = NULL, decided_by = NULL
                    WHERE id = ?
                    """, target.contributionId());
            jdbc.update("""
                    UPDATE photo_assets SET status = 'DELETED', deleted_at = ?, version = version + 1
                    WHERE id = ? AND status = 'READY'
                    """, timestamp(now), photoId);

            return new DeletedStorageKeys(target.storageKey(), target.shareViewStorageKey(), target.thumbnailStorageKey());
        });

        if (keysToDelete != null) {
            deleteStorageKeyQuietly(keysToDelete.originalKey());
            deleteStorageKeyQuietly(keysToDelete.shareViewKey());
            deleteStorageKeyQuietly(keysToDelete.thumbnailKey());
        }
    }

    @Transactional
    public List<ContributionReceipt> publishGuestPhotos(String shareToken, String guestToken, List<UUID> contributionIds) {
        GrantContext grant = resolveGrant(shareToken, true);
        GuestAlbumRow album = resolveGuestAlbum(grant, guestToken);
        List<UUID> uniqueIds = new ArrayList<>(new LinkedHashSet<>(contributionIds));

        // Short lock on mural row only for limit enforcement + contiguous position append.
        jdbc.queryForObject("SELECT id FROM murals WHERE id = ? FOR UPDATE", UUID.class, grant.muralId());
        Integer currentCountObj = jdbc.queryForObject(
                "SELECT count(*) FROM mural_photos WHERE mural_id = ?", Integer.class, grant.muralId());
        int currentCount = currentCountObj == null ? 0 : currentCountObj;
        Integer nextPosObj = jdbc.queryForObject(
                "SELECT COALESCE(MAX(position) + 1, 0) FROM mural_photos WHERE mural_id = ?",
                Integer.class, grant.muralId());
        int nextPosition = nextPosObj == null ? 0 : nextPosObj;

        Instant now = clock.instant();
        List<ContributionReceipt> receipts = new ArrayList<>(uniqueIds.size());
        UUID firstCoverCandidate = null;
        int newlyAdded = 0;

        for (UUID contributionId : uniqueIds) {
            List<GuestPublishRow> rows = jdbc.query("""
                    SELECT c.id, c.status, c.photo_id, pa.status AS photo_status, pa.share_view_status
                    FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                    WHERE c.id = ? AND c.guest_album_id = ? AND c.mural_id = ? FOR UPDATE
                    """, (rs, row) -> new GuestPublishRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getObject("photo_id", UUID.class),
                    rs.getString("photo_status"),
                    rs.getString("share_view_status")), contributionId, album.id(), grant.muralId());
            if (rows.isEmpty()) throw new MuralNotFoundException();
            GuestPublishRow item = rows.getFirst();
            if ("PUBLISHED".equals(item.status()) || "APPROVED".equals(item.status())) {
                receipts.add(new ContributionReceipt(item.id(), item.status()));
                continue;
            }
            if (!"READY".equals(item.status()) || !"READY".equals(item.photoStatus())
                    || !"READY".equals(item.shareViewStatus())) {
                throw new BirthdayModeException(HttpStatus.CONFLICT, "contribution_conflict",
                        "Uma fotografia selecionada ainda não está pronta para compartilhar.");
            }
            if (currentCount + newlyAdded >= maxPhotosPerMural) {
                throw new MuralPhotoLimitExceededException(maxPhotosPerMural);
            }
            Integer alreadyInMural = jdbc.queryForObject(
                    "SELECT count(*) FROM mural_photos WHERE mural_id = ? AND photo_id = ?",
                    Integer.class, grant.muralId(), item.photoId());
            if (alreadyInMural == null || alreadyInMural == 0) {
                jdbc.update("INSERT INTO mural_photos (id, mural_id, photo_id, position) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), grant.muralId(), item.photoId(), nextPosition++);
                newlyAdded++;
                if (firstCoverCandidate == null) {
                    firstCoverCandidate = item.photoId();
                }
            }
            jdbc.update("""
                    UPDATE contributions SET status = 'PUBLISHED', decided_at = ?, decided_by = NULL
                    WHERE id = ? AND status = 'READY'
                    """, timestamp(now), item.id());
            receipts.add(new ContributionReceipt(item.id(), "PUBLISHED"));
        }

        if (newlyAdded > 0) {
            jdbc.update("""
                    UPDATE murals SET cover_photo_id = COALESCE(cover_photo_id, ?),
                        lock_version = lock_version + 1, updated_at = ? WHERE id = ?
                    """, firstCoverCandidate, timestamp(now), grant.muralId());
        }
        return receipts;
    }

    public int cleanupExpiredContributions(int batchSize) {
        int effectiveBatch = Math.max(1, batchSize);
        List<ExpiredContribution> expired = tx.execute(status -> jdbc.query("""
                SELECT c.id AS contribution_id, pa.id AS photo_id, pa.storage_key
                FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                WHERE c.status = 'UPLOADING' AND pa.status = 'UPLOADING' AND pa.intent_expires_at <= ?
                ORDER BY pa.intent_expires_at ASC
                LIMIT ?
                """, (rs, row) -> new ExpiredContribution(
                rs.getObject("contribution_id", UUID.class),
                rs.getObject("photo_id", UUID.class),
                rs.getString("storage_key")), timestamp(clock.instant()), effectiveBatch));
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        int cleaned = 0;
        for (ExpiredContribution item : expired) {
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE contributions SET status = 'FAILED' WHERE id = ? AND status = 'UPLOADING'",
                        item.contributionId());
                jdbc.update("UPDATE photo_assets SET status = 'FAILED' WHERE id = ? AND status = 'UPLOADING'",
                        item.photoId());
            });
            try {
                storage.delete(item.storageKey());
            } catch (RuntimeException ignored) {
                // Best-effort cleanup of expired guest upload object.
            }
            cleaned++;
        }
        return cleaned;
    }

    @Transactional(readOnly = true)
    public List<ContributionView> listContributions(UUID muralId) {
        UUID ownerId = currentUser.get();
        ensureOwnedMural(ownerId, muralId);
        return jdbc.query("""
                SELECT c.id, c.status, c.display_name, c.created_at,
                       COALESCE(pa.thumbnail_storage_key, pa.share_view_storage_key, pa.storage_key) AS preview_key
                FROM contributions c JOIN photo_assets pa ON pa.id = c.photo_id
                WHERE c.mural_id = ? AND c.status IN ('PUBLISHED', 'APPROVED', 'REJECTED')
                ORDER BY c.created_at ASC, c.id ASC
                """, (rs, row) -> {
            String status = rs.getString("status");
            String previewUrl = null;
            Instant previewExpiresAt = null;
            if ("PUBLISHED".equals(status) || "APPROVED".equals(status)) {
                String key = rs.getString("preview_key");
                String cache = key.startsWith("derived/") ? PhotoStorage.PRIVATE_DERIVED_CACHE : PhotoStorage.PRIVATE_NO_STORE;
                previewUrl = storage.presignGet(key, PUBLIC_MEDIA_TTL, cache);
                previewExpiresAt = clock.instant().plus(PUBLIC_MEDIA_TTL);
            }
            return new ContributionView(rs.getObject("id", UUID.class), status, rs.getString("display_name"),
                    rs.getTimestamp("created_at").toInstant(), previewUrl, previewExpiresAt);
        }, muralId);
    }

    private void enforceContributionQuotas(GrantContext grant) {
        Integer muralPhotoCount = jdbc.queryForObject(
                "SELECT count(*) FROM mural_photos WHERE mural_id = ?", Integer.class, grant.muralId());
        if (muralPhotoCount != null && muralPhotoCount >= maxPhotosPerMural) {
            throw new MuralPhotoLimitExceededException(maxPhotosPerMural);
        }
        Integer grantContributions = jdbc.queryForObject("""
                SELECT count(*) FROM contributions
                WHERE grant_id = ? AND status IN ('UPLOADING', 'READY', 'PENDING', 'PUBLISHED', 'APPROVED')
                """, Integer.class, grant.grantId());
        if (grantContributions != null && grantContributions >= maxContributionsPerGrant) {
            throw new BirthdayModeException(HttpStatus.UNPROCESSABLE_ENTITY, "grant_contribution_limit_exceeded",
                    "Este link atingiu o limite máximo de fotografias enviadas.");
        }
    }

    private GuestAlbumRow ensureGuestAlbum(GrantContext grant, String guestToken, String displayName) {
        String tokenHash = hashGuestToken(guestToken);
        String normalizedName = normalizeDisplayName(displayName);
        List<GuestAlbumRow> existing = jdbc.query("""
                SELECT id, mural_id, grant_id, display_name FROM guest_albums WHERE token_hash = ?
                """, (rs, row) -> new GuestAlbumRow(
                rs.getObject("id", UUID.class),
                rs.getObject("mural_id", UUID.class),
                rs.getObject("grant_id", UUID.class),
                rs.getString("display_name")), tokenHash);
        if (!existing.isEmpty()) {
            GuestAlbumRow album = existing.getFirst();
            if (!album.grantId().equals(grant.grantId()) || !album.muralId().equals(grant.muralId())) {
                throw new BirthdayModeException(HttpStatus.FORBIDDEN, "guest_album_forbidden",
                        "Este álbum pessoal pertence a outra festa.");
            }
            if (normalizedName != null && !normalizedName.equals(album.displayName())) {
                jdbc.update("UPDATE guest_albums SET display_name = ? WHERE id = ?", normalizedName, album.id());
                return new GuestAlbumRow(album.id(), album.muralId(), album.grantId(), normalizedName);
            }
            return album;
        }
        UUID albumId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO guest_albums (id, mural_id, grant_id, token_hash, display_name, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, albumId, grant.muralId(), grant.grantId(), tokenHash, normalizedName,
                    timestamp(clock.instant()));
            return new GuestAlbumRow(albumId, grant.muralId(), grant.grantId(), normalizedName);
        } catch (DataIntegrityViolationException race) {
            return resolveGuestAlbum(grant, guestToken);
        }
    }

    private UUID findOptionalGuestAlbumId(GrantContext grant, String guestToken) {
        if (guestToken == null || guestToken.length() < 16 || guestToken.length() > 128
                || !guestToken.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        List<UUID> ids = jdbc.query("""
                SELECT id FROM guest_albums WHERE token_hash = ? AND grant_id = ? AND mural_id = ?
                """, (rs, row) -> rs.getObject("id", UUID.class), sha256(guestToken), grant.grantId(), grant.muralId());
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private void deleteStorageKeyQuietly(String key) {
        if (key == null || key.isBlank()) return;
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Private bucket cleanup is best-effort.
        }
    }

    private GuestAlbumRow resolveGuestAlbum(GrantContext grant, String guestToken) {
        String tokenHash = hashGuestToken(guestToken);
        List<GuestAlbumRow> rows = jdbc.query("""
                SELECT id, mural_id, grant_id, display_name FROM guest_albums
                WHERE token_hash = ? AND grant_id = ? AND mural_id = ?
                """, (rs, row) -> new GuestAlbumRow(
                rs.getObject("id", UUID.class),
                rs.getObject("mural_id", UUID.class),
                rs.getObject("grant_id", UUID.class),
                rs.getString("display_name")), tokenHash, grant.grantId(), grant.muralId());
        if (rows.isEmpty()) {
            throw new BirthdayModeException(HttpStatus.FORBIDDEN, "guest_album_required",
                    "Comece seu álbum para enviar ou compartilhar fotos.");
        }
        return rows.getFirst();
    }

    private static String hashGuestToken(String guestToken) {
        if (guestToken == null || guestToken.length() < 16 || guestToken.length() > 128
                || !guestToken.matches("[A-Za-z0-9_-]+")) {
            throw new BirthdayModeException(HttpStatus.BAD_REQUEST, "invalid_guest_token",
                    "O identificador do álbum pessoal é inválido.");
        }
        return sha256(guestToken);
    }

    private void failContributionAndDeleteObject(UUID contributionId, UploadRow upload) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE contributions SET status = 'FAILED' WHERE id = ? AND status = 'UPLOADING'", contributionId);
            jdbc.update("UPDATE photo_assets SET status = 'FAILED' WHERE id = ? AND status = 'UPLOADING'", upload.photoId());
        });
        try {
            storage.delete(upload.storageKey());
        } catch (RuntimeException ignored) {
            // Private bucket; orphan job handles any remaining object.
        }
    }

    private static String shareViewKeyFor(UUID photoId, String contentType) {
        return "derived/private/share-view/v1/" + photoId
                + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }

    private static String thumbnailKeyFor(UUID photoId) {
        return "derived/private/thumb/v1/" + photoId + ".jpg";
    }

    private GrantContext resolveGrant(String token, boolean requireContribution) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw unavailable("shared_link_unavailable", HttpStatus.NOT_FOUND);
        }
        List<GrantContext> rows = jdbc.query("""
                SELECT g.id AS grant_id, g.mural_id, g.scope, g.expires_at, g.revoked_at,
                       m.owner_id, m.status AS mural_status, m.title, m.description, m.cover_photo_id,
                       m.qr_logo_storage_key
                FROM share_grants g JOIN murals m ON m.id = g.mural_id WHERE g.token_hash = ?
                """, (rs, row) -> new GrantContext(rs.getObject("grant_id", UUID.class),
                rs.getObject("mural_id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getString("scope"),
                instantOrNull(rs.getTimestamp("expires_at")), instantOrNull(rs.getTimestamp("revoked_at")),
                rs.getString("mural_status"), rs.getString("title"), rs.getString("description"),
                rs.getObject("cover_photo_id", UUID.class), rs.getString("qr_logo_storage_key")), sha256(token));
        if (rows.isEmpty()) throw unavailable("shared_link_unavailable", HttpStatus.NOT_FOUND);
        GrantContext grant = rows.getFirst();
        if (grant.revokedAt() != null) throw unavailable("shared_link_revoked", HttpStatus.GONE);
        if (grant.expiresAt() != null && !grant.expiresAt().isAfter(clock.instant())) {
            throw unavailable("shared_link_expired", HttpStatus.GONE);
        }
        if (!"PUBLISHED".equals(grant.muralStatus())) {
            throw unavailable("shared_mural_unavailable", HttpStatus.NOT_FOUND);
        }
        if (requireContribution && !"CONTRIBUTE".equals(grant.scope())) {
            throw unavailable("contribution_not_allowed", HttpStatus.FORBIDDEN);
        }
        return grant;
    }

    private void ensureOwnedPublishedMural(UUID ownerId, UUID muralId) {
        List<String> statuses = jdbc.query("SELECT status FROM murals WHERE id = ? AND owner_id = ?",
                (rs, row) -> rs.getString("status"), muralId, ownerId);
        if (statuses.isEmpty()) throw new MuralNotFoundException();
        if (!"PUBLISHED".equals(statuses.getFirst())) {
            throw new BirthdayModeException(HttpStatus.CONFLICT, "mural_not_publishable",
                    "Publique a festa antes de gerar o QR.");
        }
    }

    private void ensureOwnedMural(UUID ownerId, UUID muralId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM murals WHERE id = ? AND owner_id = ?",
                Integer.class, muralId, ownerId);
        if (found == null || found == 0) throw new MuralNotFoundException();
    }

    private static String normalizeDisplayName(String value) {
        if (value == null) return null;
        String name = value.strip();
        if (name.isEmpty()) return null;
        if (name.codePointCount(0, name.length()) > 40) {
            throw new BirthdayModeException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_display_name",
                    "O nome pode ter até 40 caracteres.");
        }
        return name;
    }

    private static byte[] decodeQrLogo(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith(QR_LOGO_PREFIX) || dataUrl.length() > 900_000) {
            throw invalidQrLogo();
        }
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(dataUrl.substring(QR_LOGO_PREFIX.length()));
        } catch (IllegalArgumentException malformed) {
            throw invalidQrLogo();
        }
        if (encoded.length == 0 || encoded.length > MAX_QR_LOGO_BYTES) throw invalidQrLogo();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            if (input == null) throw invalidQrLogo();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidQrLogo();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 256 || height > 256) throw invalidQrLogo();
                BufferedImage image = reader.read(0);
                if (image == null) throw invalidQrLogo();
                ByteArrayOutputStream clean = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", clean) || clean.size() > MAX_QR_LOGO_BYTES) throw invalidQrLogo();
                return clean.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException invalid) {
            if (invalid instanceof BirthdayModeException birthdayError) throw birthdayError;
            throw invalidQrLogo();
        }
    }

    private static BirthdayModeException invalidQrLogo() {
        return new BirthdayModeException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_qr_logo",
                "O logo precisa ser uma imagem PNG válida de até 256 por 256 pixels.");
    }

    private GrantView toGrant(GrantRow row, String shareUrl, boolean tokenReturned) {
        String status = row.revokedAt() != null ? "REVOKED"
                : row.expiresAt() != null && !row.expiresAt().isAfter(clock.instant()) ? "EXPIRED" : "ACTIVE";
        return new GrantView(row.id(), row.scope(), status, row.createdAt(), row.expiresAt(), row.revokedAt(),
                shareUrl, tokenReturned);
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static BirthdayModeException unavailable(String code, HttpStatus status) {
        return new BirthdayModeException(status, code, "Este link não está disponível.");
    }

    private record PublicationState(String status, long lockVersion, UUID idempotencyKey, String requestHash) { }
    private record PublishPrep(boolean alreadyPublished, List<ShareViewSource> pendingSources) { }
    private record GrantRow(UUID id, String scope, Instant createdAt, Instant expiresAt, Instant revokedAt,
                            String requestHash) { }
    private record GrantContext(UUID grantId, UUID muralId, UUID ownerId, String scope, Instant expiresAt,
                                Instant revokedAt, String muralStatus, String title, String description,
                                UUID coverPhotoId, String logoStorageKey) { }
    private record GuestAlbumRow(UUID id, UUID muralId, UUID grantId, String displayName) { }
    private record UploadRow(String contributionStatus, UUID photoId, String storageKey, String contentType,
                             long sizeBytes, Instant intentExpiresAt, String photoStatus) { }
    private record GuestContributionContext(GrantContext grant, GuestAlbumRow album, UploadRow upload) { }
    private record GuestPublishRow(UUID id, String status, UUID photoId, String photoStatus, String shareViewStatus) { }
    private record ShareViewSource(UUID photoId, String storageKey, String contentType, long sizeBytes) { }
    private record ExpiredContribution(UUID contributionId, UUID photoId, String storageKey) { }
    private record GuestOwnedPhoto(UUID contributionId, String storageKey, String shareViewStorageKey,
                                   String thumbnailStorageKey) { }
    private record DeletedStorageKeys(String originalKey, String shareViewKey, String thumbnailKey) { }

    public record GuestAlbumSession(String displayName) { }
    public record GrantView(UUID grantId, String scope, String status, Instant createdAt, Instant expiresAt,
                            Instant revokedAt, String shareUrl, boolean tokenReturned) { }
    public record GrantCreation(GrantView grant, boolean replayed) { }
    public record UploadIntent(UUID contributionId, String method, String uploadUrl, Map<String, String> headers,
                               Instant expiresAt) { }
    public record ContributionReceipt(UUID contributionId, UUID photoId, String status) {
        public ContributionReceipt(UUID contributionId, String status) {
            this(contributionId, null, status);
        }
    }
    public record ContributionView(UUID contributionId, String status, String displayName, Instant createdAt,
                                   String previewUrl, Instant previewExpiresAt) { }
    public record QrLogoView(String logoUrl, Instant logoExpiresAt) { }
    public record SharedMuralView(SharedMural mural, Permissions permissions) { }
    public record SharedMural(String title, String description, UUID coverPhotoId, List<SharedPhoto> photos,
                              String logoUrl, Instant logoExpiresAt, int totalPhotos, Integer nextOffset) { }
    public record SharedPhoto(UUID photoId, int position, String contentType, long sizeBytes, int width, int height,
                              String previewUrl, String thumbnailUrl, Instant previewExpiresAt, boolean ownedByGuest,
                              String authorName) { }
    public record Permissions(boolean canView, boolean canContribute, boolean moderationRequired) { }
}
