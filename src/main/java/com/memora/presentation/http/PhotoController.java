package com.memora.presentation.http;

import com.memora.application.photo.CompletePhotoUpload;
import com.memora.application.photo.DeletePhoto;
import com.memora.application.photo.ListPhotos;
import com.memora.application.photo.PhotoPage;
import com.memora.application.photo.PhotoView;
import com.memora.application.photo.PreparePhotoUpload;
import com.memora.application.photo.PreparePhotoUploadCommand;
import com.memora.application.photo.UploadIntentResult;
import com.memora.application.ports.CurrentOwner;
import com.memora.config.PhotoLimitsProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class PhotoController {
    private final PreparePhotoUpload prepareUpload;
    private final CompletePhotoUpload completeUpload;
    private final ListPhotos listPhotos;
    private final DeletePhoto deletePhoto;
    private final CurrentOwner currentOwner;
    private final UploadRateLimiter rateLimiter;
    private final int ownerUploadsPerMinute;

    public PhotoController(PreparePhotoUpload prepareUpload, CompletePhotoUpload completeUpload,
                           ListPhotos listPhotos, DeletePhoto deletePhoto, CurrentOwner currentOwner,
                           UploadRateLimiter rateLimiter, PhotoLimitsProperties limits) {
        this.prepareUpload = prepareUpload;
        this.completeUpload = completeUpload;
        this.listPhotos = listPhotos;
        this.deletePhoto = deletePhoto;
        this.currentOwner = currentOwner;
        this.rateLimiter = rateLimiter;
        this.ownerUploadsPerMinute = limits.effectiveOwnerUploadsPerMinute();
    }

    @PostMapping("/uploads")
    public ResponseEntity<UploadIntentResult> prepare(@Valid @RequestBody CreateUploadRequest request) {
        rateLimiter.checkOrThrow("owner-upload:" + currentOwner.ownerId(), ownerUploadsPerMinute);
        UploadIntentResult result = prepareUpload.execute(new PreparePhotoUploadCommand(
                request.fileName(), request.contentType(), request.sizeBytes()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<PhotoView> complete(@PathVariable UUID uploadId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(completeUpload.execute(uploadId));
    }

    @GetMapping("/photos")
    public ResponseEntity<PhotoListResponse> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        PhotoPage page = listPhotos.execute(limit, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PhotoListResponse(page.items(), page.nextCursor()));
    }

    @DeleteMapping("/photos/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable UUID photoId) {
        deletePhoto.execute(photoId);
        return ResponseEntity.noContent().build();
    }

    public record CreateUploadRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String contentType,
            @Positive long sizeBytes
    ) {
    }

    public record PhotoListResponse(List<PhotoView> items, String nextCursor) {
    }
}
