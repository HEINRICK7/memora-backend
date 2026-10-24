package com.memora.infrastructure.storage;

import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoValidationException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;

@Component
public class S3PhotoStorage implements PhotoStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3PhotoStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
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
    public InspectedImage inspect(String storageKey, long expectedSizeBytes) {
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

        try (ResponseInputStream<GetObjectResponse> object = s3.getObject(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey)
                .build());
             ImageInputStream imageInput = new MemoryCacheImageInputStream(object)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw invalidObject();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, false, false);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw invalidObject();
                }
                int sample = (int) Math.max(1L, (Math.max((long) width, height) + 511L) / 512L);
                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(sample, sample, 0, 0);
                if (reader.read(0, readParam) == null) {
                    throw invalidObject();
                }
                return new InspectedImage(mediaType(reader.getFormatName()), actualSize, width, height);
            } finally {
                reader.dispose();
            }
        } catch (PhotoValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidObject();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw invalidObject();
            }
            throw exception;
        }
    }

    @Override
    public String presignGet(String storageKey, Duration ttl) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(storageKey)
                                .build())
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

    private String mediaType(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> throw invalidObject();
        };
    }

    private PhotoValidationException invalidObject() {
        return new PhotoValidationException(
                PhotoValidationException.Code.INVALID_CONTENT,
                "O conteúdo armazenado não é uma imagem JPEG ou PNG válida."
        );
    }
}
