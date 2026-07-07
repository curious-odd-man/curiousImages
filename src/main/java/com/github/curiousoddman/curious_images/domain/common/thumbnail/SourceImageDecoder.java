package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.util.ImageUtils.imreadUnicodeSafe;

/**
 * Decodes a source photo file to a {@link Mat} (BGR), the one place in the codebase that
 * knows "CR2 means decode the embedded preview, never the raw sensor data".
 * <p>
 * Decoding uses OpenCV's native codecs (libjpeg-turbo/libpng backed) rather than ImageIO —
 * faster, but note the channel order is BGR, not RGB; callers must account for this.
 * <p>
 * Originally this logic lived only inside {@link ThumbnailGenerator}. It's pulled out here so
 * duplicate detection's pixel hashing (see {@code domain.duplicate.PixelHasher}) decodes images
 * exactly the same way the thumbnail pipeline does — same embedded-preview source for CR2, same
 * "no usable image -> empty, don't fail the whole file" behavior — rather than maintaining two
 * slightly-different decode paths that could disagree.
 * <p>
 * Caller owns the returned Mat and must call {@code .release()} on it when done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceImageDecoder {
    private static final String CR2_EXTENSION = "cr2";

    private final PhotoMetadataExtractor metadataExtractor;

    /**
     * @return the decoded image, or empty if nothing decodable was found (corrupt file, CR2 with
     * no usable embedded preview, etc.) — never throws for a single bad file.
     */
    public Optional<Mat> decode(Path sourceFile, String extension) {
        try {
            if (CR2_EXTENSION.equalsIgnoreCase(extension)) {
                return metadataExtractor.extractEmbeddedPreviewBytes(sourceFile)
                                        .map(this::decodeBytes);
            }
            Mat img = imreadUnicodeSafe(sourceFile, Imgcodecs.IMREAD_COLOR);
            return img.empty() ? Optional.empty() : Optional.of(img);
        } catch (Exception e) {
            log.warn("Failed to decode {}", sourceFile, e);
            return Optional.empty();
        }
    }

    private Mat decodeBytes(byte[] bytes) {
        Mat encoded = new MatOfByte(bytes);
        Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        encoded.release();
        if (decoded.empty()) {
            log.warn("Failed to decode embedded preview bytes");
            return null;
        }
        return decoded;
    }
}