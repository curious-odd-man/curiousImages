package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.domain.common.thumbnail.SourceImageDecoder;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

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
        Optional<Mat> image = imageDecoder.decode(file, extension, 0);
        return image
                .map(mat -> new PhotoHashResult(photoId, extension, fileSize, file.toString(), hashPixels(mat)))
                .orElseGet(() -> new PhotoHashResult(photoId, extension, fileSize, file.toString(), null));
    }

    private String hashPixels(Mat image) {
        int width    = image.cols();
        int height   = image.rows();
        int channels = image.channels();

        MessageDigest digest = sha256();
        ByteBuffer header = ByteBuffer.allocate(8)
                                      .order(ByteOrder.BIG_ENDIAN);
        header.putInt(width)
              .putInt(height);
        digest.update(header.array());

        // Mat data is BGR byte order (imread/imdecode) — this hash will NOT match the old
        // getRGB()-based hash bit-for-bit, since getRGB() packs ARGB ints. If backward
        // compatibility with previously-stored hashes matters, do NOT ship this as-is —
        // either re-hash the whole photo library, or keep Option A instead.
        byte[] pixelBytes = new byte[width * height * channels];
        image.get(0, 0, pixelBytes);
        digest.update(pixelBytes);

        return HexFormat.of()
                        .formatHex(digest.digest());
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
