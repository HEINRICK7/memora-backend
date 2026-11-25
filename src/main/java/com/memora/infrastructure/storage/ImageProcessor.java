package com.memora.infrastructure.storage;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoValidationException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

public final class ImageProcessor {
    public static final long MAX_PIXELS = 250_000_000L;
    public static final int SHARE_VIEW_MAX_SIDE = 2048;
    public static final int THUMBNAIL_MAX_SIDE = 480;
    private static final float SHARE_VIEW_JPEG_QUALITY = 0.84f;
    private static final float THUMBNAIL_JPEG_QUALITY = 0.78f;

    public record ProcessedResult(
            InspectedImage original,
            InspectedImage shareView,
            byte[] shareViewBytes,
            InspectedImage thumbnail,
            byte[] thumbnailBytes
    ) {
    }

    public InspectedImage inspectOnly(Path sourceFile, long actualSizeBytes) {
        int orientation = readExifOrientation(sourceFile);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(sourceFile.toFile())) {
            if (imageInput == null) throw invalidObject();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw invalidObject();
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int rawWidth = reader.getWidth(0);
                int rawHeight = reader.getHeight(0);
                validateDimensions(rawWidth, rawHeight);
                String contentType = mediaType(reader.getFormatName());
                int sample = (int) Math.max(1L, (Math.max((long) rawWidth, rawHeight) + 511L) / 512L);
                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, readParam);
                if (decoded == null) throw invalidObject();
                decoded.flush();
                boolean swapped = orientation >= 5 && orientation <= 8;
                int logicalWidth = swapped ? rawHeight : rawWidth;
                int logicalHeight = swapped ? rawWidth : rawHeight;
                return new InspectedImage(contentType, actualSizeBytes, logicalWidth, logicalHeight);
            } finally {
                reader.dispose();
            }
        } catch (PhotoValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidObject();
        }
    }

    public ProcessedResult process(
            Path sourceFile,
            long actualSizeBytes,
            String expectedContentType,
            long maxOutputBytes
    ) {
        int orientation = readExifOrientation(sourceFile);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(sourceFile.toFile())) {
            if (imageInput == null) throw invalidObject();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw invalidObject();
            ImageReader reader = readers.next();
            BufferedImage decoded = null;
            BufferedImage oriented = null;
            BufferedImage shareImage = null;
            BufferedImage thumbImage = null;
            try {
                reader.setInput(imageInput, true, true);
                int rawWidth = reader.getWidth(0);
                int rawHeight = reader.getHeight(0);
                validateDimensions(rawWidth, rawHeight);

                String detectedType = mediaType(reader.getFormatName());
                if (expectedContentType != null && !expectedContentType.equals(detectedType)) {
                    throw new PhotoValidationException(
                            PhotoValidationException.Code.INVALID_CONTENT,
                            "O conteúdo armazenado não corresponde ao tipo informado."
                    );
                }

                // Keep memory bounded on giant originals while keeping >= 2x pixels for smooth downscaling.
                int maxRawSide = Math.max(rawWidth, rawHeight);
                int coarseSample = Math.max(1, maxRawSide / (SHARE_VIEW_MAX_SIDE * 2));
                ImageReadParam readParam = reader.getDefaultReadParam();
                if (coarseSample > 1) {
                    readParam.setSourceSubsampling(coarseSample, coarseSample, 0, 0);
                }

                decoded = reader.read(0, readParam);
                if (decoded == null) throw invalidObject();

                boolean swapped = orientation >= 5 && orientation <= 8;
                int logicalWidth = swapped ? rawHeight : rawWidth;
                int logicalHeight = swapped ? rawWidth : rawHeight;

                oriented = applyExifOrientation(decoded, orientation, "image/png".equals(detectedType));
                if (oriented != decoded) {
                    decoded.flush();
                    decoded = null;
                }

                boolean preserveAlpha = "image/png".equals(detectedType);
                shareImage = resizeSmooth(oriented, SHARE_VIEW_MAX_SIDE, preserveAlpha);
                byte[] shareBytes = encodeWithoutMetadata(shareImage, detectedType, SHARE_VIEW_JPEG_QUALITY);
                if (shareBytes.length == 0 || shareBytes.length > maxOutputBytes) {
                    throw invalidObject();
                }

                // Build thumbnail from shareImage (already oriented and bounded <= 2560 px)
                thumbImage = resizeSmooth(shareImage, THUMBNAIL_MAX_SIDE, false);
                byte[] thumbBytes = encodeWithoutMetadata(thumbImage, "image/jpeg", THUMBNAIL_JPEG_QUALITY);
                if (thumbBytes.length == 0 || thumbBytes.length > maxOutputBytes) {
                    throw invalidObject();
                }

                InspectedImage originalInfo = new InspectedImage(detectedType, actualSizeBytes, logicalWidth, logicalHeight);
                InspectedImage shareInfo = new InspectedImage(
                        detectedType, shareBytes.length, shareImage.getWidth(), shareImage.getHeight()
                );
                InspectedImage thumbInfo = new InspectedImage(
                        "image/jpeg", thumbBytes.length, thumbImage.getWidth(), thumbImage.getHeight()
                );
                return new ProcessedResult(originalInfo, shareInfo, shareBytes, thumbInfo, thumbBytes);
            } finally {
                if (thumbImage != null && thumbImage != shareImage) thumbImage.flush();
                if (shareImage != null && shareImage != oriented) shareImage.flush();
                if (oriented != null) oriented.flush();
                if (decoded != null) decoded.flush();
                reader.dispose();
            }
        } catch (PhotoValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidObject();
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw invalidObject();
        }
    }

    private static int readExifOrientation(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (orientation >= 1 && orientation <= 8) {
                    return orientation;
                }
            }
        } catch (Exception ignored) {
            // Missing or non-standard EXIF defaults to upright orientation.
        }
        return 1;
    }

    private static BufferedImage applyExifOrientation(BufferedImage src, int orientation, boolean preserveAlpha) {
        if (orientation == 1) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swapped = orientation >= 5 && orientation <= 8;
        int targetW = swapped ? h : w;
        int targetH = swapped ? w : h;

        AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 2 -> { // Flip horizontal
                tx.scale(-1.0, 1.0);
                tx.translate(-w, 0);
            }
            case 3 -> { // Rotate 180
                tx.translate(w, h);
                tx.rotate(Math.PI);
            }
            case 4 -> { // Flip vertical
                tx.scale(1.0, -1.0);
                tx.translate(0, -h);
            }
            case 5 -> { // Transpose
                tx.rotate(Math.PI / 2.0);
                tx.scale(1.0, -1.0);
            }
            case 6 -> { // Rotate 90 CW
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2.0);
            }
            case 7 -> { // Transverse
                tx.scale(-1.0, 1.0);
                tx.translate(-h, 0);
                tx.translate(0, w);
                tx.rotate(3.0 * Math.PI / 2.0);
            }
            case 8 -> { // Rotate 270 CW (90 CCW)
                tx.translate(0, w);
                tx.rotate(3.0 * Math.PI / 2.0);
            }
            default -> {
                return src;
            }
        }

        int type = preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dest = new BufferedImage(targetW, targetH, type);
        Graphics2D g = dest.createGraphics();
        try {
            if (!preserveAlpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetW, targetH);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, tx, null);
        } finally {
            g.dispose();
        }
        return dest;
    }

    private static BufferedImage resizeSmooth(BufferedImage src, int maxSide, boolean preserveAlpha) {
        int currentW = src.getWidth();
        int currentH = src.getHeight();
        int longest = Math.max(currentW, currentH);
        int targetType = preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        if (longest <= maxSide) {
            if (src.getType() == targetType) {
                return src;
            }
            return drawScaled(src, currentW, currentH, targetType, preserveAlpha, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }

        double ratio = (double) maxSide / longest;
        int targetW = Math.max(1, (int) Math.round(currentW * ratio));
        int targetH = Math.max(1, (int) Math.round(currentH * ratio));

        // Multi-step halving avoids aliasing artifacts when downscaling > 2x.
        BufferedImage current = src;
        int w = currentW;
        int h = currentH;
        while (w / 2 > targetW && h / 2 > targetH) {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            BufferedImage step = drawScaled(current, w, h, targetType, preserveAlpha, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (current != src) {
                current.flush();
            }
            current = step;
        }

        BufferedImage result = drawScaled(current, targetW, targetH, targetType, preserveAlpha, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        if (current != src) {
            current.flush();
        }
        return result;
    }

    private static BufferedImage drawScaled(
            BufferedImage source,
            int targetW,
            int targetH,
            int imageType,
            boolean preserveAlpha,
            Object interpolationHint
    ) {
        BufferedImage scaled = new BufferedImage(targetW, targetH, imageType);
        Graphics2D g = scaled.createGraphics();
        try {
            if (!preserveAlpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, targetW, targetH);
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolationHint);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static byte[] encodeWithoutMetadata(BufferedImage image, String contentType, float jpegQuality) throws IOException {
        String format = "image/png".equals(contentType) ? "png" : "jpeg";
        BufferedImage encodable = image;
        if ("jpeg".equals(format) && image.getColorModel().hasAlpha()) {
            encodable = drawScaled(image, image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB, false,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) throw invalidObject();
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            if (output == null) throw invalidObject();
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed() && "jpeg".equals(format)) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(jpegQuality);
            }
            writer.write(null, new IIOImage(encodable, null, null), params);
            output.flush();
            return bytes.toByteArray();
        } finally {
            if (encodable != image) {
                encodable.flush();
            }
            writer.dispose();
        }
    }

    private static String mediaType(String formatName) {
        if (formatName == null) {
            throw invalidObject();
        }
        return switch (formatName.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> throw new PhotoValidationException(
                    PhotoValidationException.Code.UNSUPPORTED_MEDIA_TYPE,
                    "O arquivo enviado não é uma imagem JPEG ou PNG válida."
            );
        };
    }

    private static PhotoValidationException invalidObject() {
        return new PhotoValidationException(
                PhotoValidationException.Code.INVALID_CONTENT,
                "O arquivo enviado não pôde ser validado como fotografia."
        );
    }
}
