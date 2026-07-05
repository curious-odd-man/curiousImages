package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.util.ImageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Generates and caches 512px (longest-edge) JPEG thumbnails for imported photos.
 * <p>
 * Decoding itself (including the CR2-embedded-preview special case) now lives in
 * {@link SourceImageDecoder}, shared with duplicate detection's pixel hashing — this class is
 * "decode, rotate to the correct orientation, resize, write to cache path". Rotation is baked
 * into the cached file: nothing that displays a thumbnail (grid, duplicates review) needs to
 * apply its own rotation transform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerator {
    /**
     * Default longest-edge size (pixels) for grid/duplicates thumbnails. Public so
     * {@code ThumbnailGenerationJob} — which decodes on its own reader thread and calls
     * {@link #writeThumbnail} directly on a writer pool, bypassing {@link #generate} — can reuse
     * the same value rather than hard-coding it a second time.
     */
    public static final int LONGEST_EDGE = 512;

    private final ThumbnailCachePaths cachePaths;
    private final SourceImageDecoder  imageDecoder;

    public record GeneratedThumbnail(Path cachePath, int width, int height) {
    }

    /**
     * Decodes {@code sourceFile}, rotates it clockwise by {@code rotationDegrees} (must be one of
     * 0/90/180/270 — see {@code PhotoMetadataExtractor}'s EXIF Orientation mapping, the only
     * producer of this value), resizes it (longest edge = {@value #LONGEST_EDGE}px, aspect ratio
     * preserved) and writes it to this photo's deterministic shard path. Returns empty if no
     * image could be decoded at all (e.g. a CR2 with no usable embedded preview) — the caller
     * should simply not create a THUMBNAIL row in that case and let the UI fall back to
     * {@code img/noimage.png}, rather than failing the whole file's import.
     */
    public Optional<GeneratedThumbnail> generate(Path sourceFile, String extension, int rotationDegrees) {
        BufferedImage source = imageDecoder.decode(sourceFile, extension)
                                           .orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(writeThumbnail(source, sourceFile, rotationDegrees, LONGEST_EDGE));
        } catch (IOException e) {
            log.warn("Failed to write thumbnail for {}", sourceFile, e);
            return Optional.empty();
        }
    }

    /**
     * Regenerates the cached thumbnail for {@code photoId} if the file at its deterministic shard
     * path is missing — e.g. because the cache directory was cleared. The import job itself
     * always generates fresh thumbnails via {@link #generate}, so it won't usually hit this path;
     * this method exists for the Grid view, which calls it on every thumbnail load.
     *
     * @param rotationDegrees the photo's stored {@code ORIENTATION} (clockwise degrees) — callers
     *                        have a {@code PhotoRecord} in hand at the point they call this, so
     *                        pass {@code photo.getOrientation()} through rather than re-deriving it
     * @return {@code true} if a thumbnail was (re)generated, {@code false} if one already existed
     * on disk and nothing needed to be done
     */
    public boolean ensureThumbnail(Path sourceFile, String extension, int rotationDegrees) {
        Path resolved = cachePaths.resolve(sourceFile);
        if (Files.exists(resolved)) {
            return false;
        }
        return generate(sourceFile, extension, rotationDegrees).isPresent();
    }

    public GeneratedThumbnail writeThumbnail(BufferedImage source, Path sourceFile, int rotationDegrees, int longestEdge) throws IOException {
        Path target = cachePaths.resolve(sourceFile);
        Files.createDirectories(target.getParent());
        List<Integer> allowedDegrees = List.of(0, 90, 180, 270);
        if (!allowedDegrees.contains(Math.abs(rotationDegrees))) {
            log.error("Unknown rotation degrees!!!");
        }

        BufferedImage oriented = rotate(source, rotationDegrees);

        // Thumbnailator's size(w, h) with keepAspectRatio(true) constrains the *longest* edge to
        // the given box while preserving aspect ratio — exactly the "longest edge = 512px"
        // requirement from the product spec. Rotation already happened above, so this just resizes.
        BufferedImage resized = Thumbnails.of(oriented)
                                          .size(longestEdge, longestEdge)
                                          .keepAspectRatio(true)
                                          .asBufferedImage();

        ImageIO.write(resized, "jpg", target.toFile());

        return new GeneratedThumbnail(target, resized.getWidth(), resized.getHeight());
    }

    /**
     * Rotates {@code source} clockwise by {@code degrees}, swapping canvas dimensions for 90/270.
     * {@code degrees} outside {0, 90, 180, 270} is normalized into that set first; values are
     * always one of these in practice (see {@code PhotoMetadataExtractor}) but normalizing keeps
     * this method safe to call with an arbitrary stored value too.
     */
    public static BufferedImage rotate(BufferedImage source, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized != 90 && normalized != 180 && normalized != 270) {
            return source;
        }

        BufferedImage rotated = switch (normalized) {
            case 90 -> ImageUtils.rotate90(source);
            case 180 -> ImageUtils.rotate180(source);
            case 270 -> ImageUtils.rotate270(source);
            default -> source;
        };

        Graphics2D g = rotated.createGraphics();
        try {
            g.drawImage(rotated, null, null);
        } finally {
            g.dispose();
        }
        return rotated;
    }
}
