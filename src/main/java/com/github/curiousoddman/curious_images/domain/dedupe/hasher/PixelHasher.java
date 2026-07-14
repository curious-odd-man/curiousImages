package com.github.curiousoddman.curious_images.domain.dedupe.hasher;

import com.github.curiousoddman.curious_images.util.ImageUtils;
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

@Component
@RequiredArgsConstructor
public class PixelHasher {
    private final ImageUtils imageUtils;

    /**
     * @return a result with a non-null {@code pixelHash}, or one with a {@code null} pixelHash if
     * the file couldn't be decoded at all (corrupt file, CR2 with no usable preview). The latter
     * is not an error — the caller should simply skip that photo for this run, same policy as
     * thumbnail generation skipping undecodable files during import.
     */
    public PhotoHashResult hash(long photoId, Path file, String extension, long fileSize) {
        Optional<Mat> image = imageUtils.imageOrCr2Preview(file, extension, 0);
        return image
                .map(mat -> new PhotoHashResult(photoId, extension, fileSize, file.toString(), hashPixels(mat)))
                .orElseGet(() -> new PhotoHashResult(photoId, extension, fileSize, file.toString(), null));
    }

    String hashPixels(Mat image) {
        int width    = image.cols();
        int height   = image.rows();
        int channels = image.channels();

        MessageDigest digest = sha256();
        ByteBuffer header = ByteBuffer.allocate(8)
                                      .order(ByteOrder.BIG_ENDIAN);
        header.putInt(width)
              .putInt(height);
        digest.update(header.array());

        byte[] pixelBytes = new byte[width * height * channels];
        image.get(0, 0, pixelBytes);
        digest.update(pixelBytes);

        return HexFormat.of()
                        .formatHex(digest.digest());
    }

    static MessageDigest sha256() {
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
