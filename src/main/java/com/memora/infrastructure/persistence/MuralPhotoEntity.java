package com.memora.infrastructure.persistence;

import com.memora.domain.mural.MuralPhoto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "mural_photos")
public class MuralPhotoEntity {
    @Id
    private UUID id;

    @Column(name = "mural_id", nullable = false)
    private UUID muralId;

    @Column(name = "photo_id", nullable = false)
    private UUID photoId;

    @Column(nullable = false)
    private int position;

    protected MuralPhotoEntity() {
    }

    private MuralPhotoEntity(UUID id, UUID muralId, UUID photoId, int position) {
        this.id = id;
        this.muralId = muralId;
        this.photoId = photoId;
        this.position = position;
    }

    public static MuralPhotoEntity fromDomain(MuralPhoto photo) {
        return new MuralPhotoEntity(UUID.randomUUID(), photo.muralId(), photo.photoId(), photo.position());
    }

    public MuralPhoto toDomain() {
        return new MuralPhoto(muralId, photoId, position);
    }

    public UUID photoId() {
        return photoId;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
