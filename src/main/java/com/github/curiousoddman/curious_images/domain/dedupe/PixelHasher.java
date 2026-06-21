package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.domain.common.thumbnail.SourceImageDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes a hash of a photo's decoded pixel content.
 * <p>
 * Hashing the decoded raster (rather than the file's raw bytes) is what makes "same JPEG with
 * different EXIF" and "same JPEG renamed" both hash identically: re-encoding metadata doesn't
 * touch pixel data. For CR2, {@link SourceImageDecoder} decodes the embedded preview — exactly
 * the same source the thumbnail pipeline uses — so this never performs a full RAW render; a CR2's
 * "pixel content" for duplicate-detection purposes is its embedded preview's pixel content.
 */
@Component
@RequiredArgsConstructor
public class PixelHasher {
    private final SourceImageDecoder imageDecoder;

    /**
     * @return a result with a non-null {@code pixelHash}, or one with a {@code null} pixelHash if
     * the file couldn't be decoded at all (corrupt file, CR2 with no usable preview). The latter
     * is not an error — the caller should simply skip that photo for this run, same policy as
     * thumbnail generation skipping undecodable files during import.
     */
    public PhotoHashResult hash(long photoId, Path file, String extension, long fileSize) {
        BufferedImage image = imageDecoder.decode(file, extension).orElse(null);
        if (image == null) {
            return new PhotoHashResult(photoId, extension, fileSize, file.toString(), null);
        }
        return new PhotoHashResult(photoId, extension, fileSize, file.toString(), hashPixels(image));
    }

    private String hashPixels(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        // Dimensions are folded into the hash so two different-sized images can never collide
        // purely by coincidence of getRGB() output.
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);

        MessageDigest digest = sha256();
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        header.putInt(width).putInt(height);
        digest.update(header.array());

        ByteBuffer pixelBytes = ByteBuffer.allocate(pixels.length * 4).order(ByteOrder.BIG_ENDIAN);
        pixelBytes.asIntBuffer().put(pixels);
        digest.update(pixelBytes.array());

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required JDK algorithm — this is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record PhotoHashResult(long photoId, String extension, long fileSize, String absolutePath,
                                  String pixelHash) {
    }
}
