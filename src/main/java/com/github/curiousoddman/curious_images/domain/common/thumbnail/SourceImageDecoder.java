package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes a source photo file to a {@link BufferedImage}, the one place in the codebase that
 * knows "CR2 means decode the embedded preview, never the raw sensor data".
 * <p>
 * Originally this logic lived only inside {@link ThumbnailGenerator}. It's pulled out here so
 * duplicate detection's pixel hashing (see {@code domain.duplicate.PixelHasher}) decodes images
 * exactly the same way the thumbnail pipeline does — same embedded-preview source for CR2, same
 * "no usable image -> empty, don't fail the whole file" behavior — rather than maintaining two
 * slightly-different decode paths that could disagree.
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
    public Optional<BufferedImage> decode(Path sourceFile, String extension) {
        try {
            if (CR2_EXTENSION.equalsIgnoreCase(extension)) {
                return metadataExtractor.extractEmbeddedPreviewBytes(sourceFile)
                                        .map(this::decodeBytes)
                                        .filter(Objects::nonNull);
            }
            return Optional.ofNullable(ImageIO.read(sourceFile.toFile()));
        } catch (IOException e) {
            log.warn("Failed to decode {}", sourceFile, e);
            return Optional.empty();
        }
    }

    private BufferedImage decodeBytes(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }
}
