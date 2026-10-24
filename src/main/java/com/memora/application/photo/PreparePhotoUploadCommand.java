package com.memora.application.photo;

public record PreparePhotoUploadCommand(String fileName, String contentType, long sizeBytes) {
}
