package com.memora.infrastructure.persistence;

import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "murals", indexes = @Index(name = "idx_murals_owner_created_id",
        columnList = "owner_id,created_at,id"))
public class MuralEntity {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MuralStatus status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MuralEntity() {
    }

    private MuralEntity(Mural mural, UUID idempotencyKey, String requestHash) {
        id = mural.id();
        ownerId = mural.ownerId();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        title = mural.title();
        description = mural.description();
        status = mural.status();
        lockVersion = 0;
        createdAt = mural.createdAt();
        updatedAt = mural.updatedAt();
    }

    public static MuralEntity fromDomain(Mural mural, UUID idempotencyKey, String requestHash) {
        return new MuralEntity(mural, idempotencyKey, requestHash);
    }

    public Mural toDomain() {
        return Mural.restore(id, ownerId, status, Math.toIntExact(lockVersion + 1),
                title, description, createdAt, updatedAt);
    }

    public void updateMetadata(Mural mural) {
        title = mural.title();
        description = mural.description();
        updatedAt = mural.updatedAt();
    }

    public String requestHash() {
        return requestHash;
    }
}
