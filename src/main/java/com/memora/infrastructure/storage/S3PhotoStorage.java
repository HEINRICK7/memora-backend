package com.memora.infrastructure.storage;

import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoValidationException;
import com.memora.domain.photo.ProcessedImage;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@Component
public class S3PhotoStorage implements PhotoStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;
    private final ImageProcessor imageProcessor;
    private final Semaphore processingSlots;

    public S3PhotoStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.imageProcessor = new ImageProcessor();
        this.processingSlots = new Semaphore(properties.effectiveMaxConcurrentProcessing(), true);
    }

    @Override
    public String presignPut(String storageKey, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(putObjectRequest)
                        .build())
                .url()
                .toString();
    }

    @Override
    public void putPrivateObject(String storageKey, String contentType, byte[] content) {
        putPrivateObject(storageKey, contentType, content, PRIVATE_NO_STORE);
    }

    private void putPrivateObject(String storageKey, String contentType, byte[] content, String cacheControl) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(storageKey)
                        .contentType(contentType)
                        .contentLength((long) content.length)
                        .cacheControl(cacheControl)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public InspectedImage inspect(String storageKey, long expectedSizeBytes) {
        long actualSize = verifyObjectSize(storageKey, expectedSizeBytes);
        return withProcessingSlot(() -> {
            Path tempFile = downloadToTempFile(storageKey);
            try {
                return imageProcessor.inspectOnly(tempFile, actualSize);
            } finally {
                deleteTempFile(tempFile);
            }
        });
    }

    @Override
    public InspectedImage prepareShareView(String sourceKey, String targetKey, long expectedSizeBytes) {
        long actualSize = verifyObjectSize(sourceKey, expectedSizeBytes);
        ImageProcessor.ProcessedResult processed = withProcessingSlot(() -> {
            Path tempFile = downloadToTempFile(sourceKey);
            try {
                return imageProcessor.process(tempFile, actualSize, null, properties.maxUploadBytes());
            } finally {
                deleteTempFile(tempFile);
            }
        });
        putPrivateObject(targetKey, processed.shareView().contentType(), processed.shareViewBytes(), PRIVATE_DERIVED_CACHE);
        return processed.shareView();
    }

    @Override
    public ProcessedImage processUploadedPhoto(
            String sourceKey,
            String shareViewKey,
            String thumbnailKey,
            long expectedSizeBytes,
            String expectedContentType
    ) {
        long actualSize = verifyObjectSize(sourceKey, expectedSizeBytes);
        ImageProcessor.ProcessedResult processed = withProcessingSlot(() -> {
            Path tempFile = downloadToTempFile(sourceKey);
            try {
                return imageProcessor.process(tempFile, actualSize, expectedContentType, properties.maxUploadBytes());
            } finally {
                deleteTempFile(tempFile);
            }
        });
        putPrivateObject(shareViewKey, processed.shareView().contentType(), processed.shareViewBytes(), PRIVATE_DERIVED_CACHE);
        putPrivateObject(thumbnailKey, processed.thumbnail().contentType(), processed.thumbnailBytes(), PRIVATE_DERIVED_CACHE);
        return new ProcessedImage(processed.original(), processed.shareView(), processed.thumbnail());
    }

    @Override
    public String presignGet(String storageKey, Duration ttl) {
        String cacheControl = storageKey != null && storageKey.startsWith("derived/")
                ? PRIVATE_DERIVED_CACHE
                : PRIVATE_NO_STORE;
        return presignGet(storageKey, ttl, cacheControl);
    }

    @Override
    public String presignGet(String storageKey, Duration ttl, String cacheControl) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .responseCacheControl(cacheControl)
                .build();

        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(getObjectRequest)
                        .build())
                .url()
                .toString();
    }

    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build());
    }

    public void ensurePrivateBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
        }
        s3.deleteBucketPolicy(DeleteBucketPolicyRequest.builder().bucket(properties.bucket()).build());
    }

    private long verifyObjectSize(String storageKey, long expectedSizeBytes) {
        long actualSize;
        try {
            actualSize = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(storageKey)
                    .build()).contentLength();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw invalidObject();
            }
            throw exception;
        }
        if (actualSize <= 0 || actualSize != expectedSizeBytes || actualSize > properties.maxUploadBytes()) {
            throw invalidObject();
        }
        return actualSize;
    }

    private Path downloadToTempFile(String storageKey) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("memora-img-", ".tmp");
            try (ResponseInputStream<GetObjectResponse> object = s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(storageKey)
                    .build())) {
                Files.copy(object, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (S3Exception exception) {
            deleteTempFile(tempFile);
            if (exception.statusCode() == 404) {
                throw invalidObject();
            }
            throw exception;
        } catch (IOException exception) {
            deleteTempFile(tempFile);
            throw invalidObject();
        }
    }

    private <T> T withProcessingSlot(java.util.function.Supplier<T> task) {
        boolean acquired = false;
        try {
            processingSlots.acquire();
            acquired = true;
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Processamento de imagem interrompido.", interrupted);
        } finally {
            if (acquired) {
                processingSlots.release();
            }
        }
    }

    private static void deleteTempFile(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Temp cleanup is best-effort.
        }
    }

    private static PhotoValidationException invalidObject() {
        return new PhotoValidationException(
                PhotoValidationException.Code.INVALID_CONTENT,
                "O arquivo enviado não pôde ser validado como fotografia."
        );
    }
}
