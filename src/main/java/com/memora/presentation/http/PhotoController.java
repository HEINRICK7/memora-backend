package com.memora.presentation.http;

import com.memora.application.photo.CompletePhotoUpload;
import com.memora.application.photo.DeletePhoto;
import com.memora.application.photo.ListPhotos;
import com.memora.application.photo.PhotoView;
import com.memora.application.photo.PreparePhotoUpload;
import com.memora.application.photo.PreparePhotoUploadCommand;
import com.memora.application.photo.UploadIntentResult;
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

    public PhotoController(PreparePhotoUpload prepareUpload, CompletePhotoUpload completeUpload,
                           ListPhotos listPhotos, DeletePhoto deletePhoto) {
        this.prepareUpload = prepareUpload;
        this.completeUpload = completeUpload;
        this.listPhotos = listPhotos;
        this.deletePhoto = deletePhoto;
    }

    @PostMapping("/uploads")
    public ResponseEntity<UploadIntentResult> prepare(@Valid @RequestBody CreateUploadRequest request) {
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
    public ResponseEntity<PhotoListResponse> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PhotoListResponse(listPhotos.execute()));
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

    public record PhotoListResponse(List<PhotoView> items) {
    }
}
