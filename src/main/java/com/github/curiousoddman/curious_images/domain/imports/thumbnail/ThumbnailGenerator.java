package com.github.curiousoddman.curious_images.domain.imports.thumbnail;

import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Generates and caches 512px (longest-edge) JPEG thumbnails for imported photos.
 * <p>
 * Same code path regardless of source format — this is what "do not perform full RAW rendering"
 * means in practice (see implementation plan §10): a CR2 never goes through a raw decoder, only
 * its already-JPEG-encoded embedded preview does, via {@link PhotoMetadataExtractor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerator {
    private static final int LONGEST_EDGE = 512;
    private static final String CR2_EXTENSION = "cr2";

    private final ThumbnailCachePaths cachePaths;
    private final PhotoMetadataExtractor metadataExtractor;

    public record GeneratedThumbnail(String cachePath, int width, int height) {
    }

    /**
     * Decodes {@code sourceFile}, resizes it (longest edge = {@value #LONGEST_EDGE}px, aspect
     * ratio preserved) and writes it to this photo's deterministic shard path. Returns empty if
     * no image could be decoded at all (e.g. a CR2 with no usable embedded preview) — the caller
     * should simply not create a THUMBNAIL row in that case and let the UI fall back to
     * {@code img/noimage.png}, rather than failing the whole file's import.
     */
    public Optional<GeneratedThumbnail> generate(long photoId, Path sourceFile, String extension) {
        BufferedImage source = decodeSourceImage(sourceFile, extension);
        if (source == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(writeThumbnail(photoId, source, sourceFile));
        } catch (IOException e) {
            log.warn("Failed to write thumbnail for {}", sourceFile, e);
            return Optional.empty();
        }
    }

    /**
     * Regenerates the cached thumbnail for {@code photoId} if the file at its deterministic shard
     * path is missing — e.g. because the cache directory was cleared. The import job itself
     * always generates fresh thumbnails via {@link #generate}, so it won't usually hit this path;
     * this method exists for the future Grid view, which will call it on every thumbnail load.
     *
     * @return {@code true} if a thumbnail was (re)generated, {@code false} if one already existed
     * on disk and nothing needed to be done
     */
    public boolean ensureThumbnail(long photoId, Path sourceFile, String extension) {
        Path resolved = cachePaths.resolve(sourceFile);
        if (Files.exists(resolved)) {
            return false;
        }
        return generate(photoId, sourceFile, extension).isPresent();
    }

    private BufferedImage decodeSourceImage(Path sourceFile, String extension) {
        try {
            if (CR2_EXTENSION.equalsIgnoreCase(extension)) {
                return metadataExtractor.extractEmbeddedPreviewBytes(sourceFile)
                        .map(this::decodeBytes)
                        .orElse(null);
            }
            return ImageIO.read(sourceFile.toFile());
        } catch (IOException e) {
            log.warn("Failed to decode {} for thumbnail generation", sourceFile, e);
            return null;
        }
    }

    private BufferedImage decodeBytes(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }

    private GeneratedThumbnail writeThumbnail(long photoId, BufferedImage source, Path sourceFile) throws IOException {
        Path target = cachePaths.resolve(sourceFile);
        Files.createDirectories(target.getParent());

        // Thumbnailator's size(w, h) with keepAspectRatio(true) constrains the *longest* edge to
        // the given box while preserving aspect ratio — exactly the "longest edge = 512px"
        // requirement from the product spec.
        BufferedImage resized = Thumbnails.of(source)
                .size(LONGEST_EDGE, LONGEST_EDGE)
                .keepAspectRatio(true)
                .asBufferedImage();

        ImageIO.write(resized, "jpg", target.toFile());

        return new GeneratedThumbnail(target.toString(), resized.getWidth(), resized.getHeight());
    }
}
