package com.memora.infrastructure.storage;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.memora.domain.photo.PhotoValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void generatesShareViewAndThumbnailInSinglePassAndStripsExif(@TempDir Path tempDir) throws Exception {
        BufferedImage source = new BufferedImage(3200, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(new Color(0x475CFA));
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(new Color(0xD9FD61));
        g.fillRect(1600, 0, 1600, 1600);
        g.dispose();

        byte[] rawJpeg = toJpegWithExifOrientation(source, 6); // 90 deg CW: 3200x1600 -> 1600x3200
        Path file = tempDir.resolve("portrait-exif6.jpg");
        Files.write(file, rawJpeg);

        ImageProcessor.ProcessedResult result = processor.process(file, rawJpeg.length, "image/jpeg", MAX_BYTES);

        // Logical dimensions after EXIF orientation 6 (90 deg clockwise)
        assertThat(result.original().width()).isEqualTo(1600);
        assertThat(result.original().height()).isEqualTo(3200);
        assertThat(result.original().contentType()).isEqualTo("image/jpeg");

        // Share view scaled smoothly to <= 2560 on longest edge, preserving 1:2 portrait aspect
        assertThat(result.shareView().width()).isEqualTo(1280);
        assertThat(result.shareView().height()).isEqualTo(2560);
        assertThat(result.shareViewBytes()).isNotEmpty();

        // Thumbnail scaled smoothly to <= 480 on longest edge
        assertThat(result.thumbnail().width()).isEqualTo(240);
        assertThat(result.thumbnail().height()).isEqualTo(480);
        assertThat(result.thumbnailBytes()).isNotEmpty();

        // EXIF stripped from both derivatives
        Metadata shareMeta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(result.shareViewBytes()));
        assertThat(shareMeta.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
        Metadata thumbMeta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(result.thumbnailBytes()));
        assertThat(thumbMeta.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();

        // Verify actual pixel rotation: original left half (blue) becomes top half after 90 CW rotation
        BufferedImage decodedThumb = ImageIO.read(new ByteArrayInputStream(result.thumbnailBytes()));
        Color topPixel = new Color(decodedThumb.getRGB(120, 40));
        Color bottomPixel = new Color(decodedThumb.getRGB(120, 440));
        assertThat(topPixel.getBlue()).isGreaterThan(180);
        assertThat(bottomPixel.getGreen()).isGreaterThan(180);
    }

    @Test
    void rejectsContentTypeMismatch(@TempDir Path tempDir) throws Exception {
        BufferedImage source = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Path file = tempDir.resolve("actual.png");
        ImageIO.write(source, "png", file.toFile());
        long size = Files.size(file);

        assertThatThrownBy(() -> processor.process(file, size, "image/jpeg", MAX_BYTES))
                .isInstanceOf(PhotoValidationException.class);
    }

    private static byte[] toJpegWithExifOrientation(BufferedImage image, int orientation) throws Exception {
        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", jpegOut);
        byte[] jpeg = jpegOut.toByteArray();

        // Build minimal APP1 Exif segment with single Orientation tag (0x0112)
        ByteBuffer tiff = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M'); // Big-endian TIFF
        tiff.putShort((short) 42);            // TIFF magic
        tiff.putInt(8);                       // Offset to IFD0
        tiff.putShort((short) 1);             // 1 directory entry
        tiff.putShort((short) 0x0112);        // Tag: Orientation
        tiff.putShort((short) 3);             // Type: SHORT
        tiff.putInt(1);                       // Count: 1
        tiff.putShort((short) orientation);   // Value
        tiff.putShort((short) 0);             // Padding
        tiff.putInt(0);                       // Next IFD offset

        byte[] exifHeader = new byte[]{'E', 'x', 'i', 'f', 0, 0};
        int segmentLength = 2 + exifHeader.length + tiff.capacity();

        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(jpeg, 0, 2); // SOI (FFD8)
        withExif.write(0xFF);
        withExif.write(0xE1); // APP1
        withExif.write((segmentLength >> 8) & 0xFF);
        withExif.write(segmentLength & 0xFF);
        withExif.write(exifHeader);
        withExif.write(tiff.array());
        withExif.write(jpeg, 2, jpeg.length - 2);
        return withExif.toByteArray();
    }
}
