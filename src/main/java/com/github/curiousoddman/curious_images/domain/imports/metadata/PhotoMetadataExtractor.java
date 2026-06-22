package com.github.curiousoddman.curious_images.domain.imports.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts capture date, full-resolution width/height, orientation, camera/lens identity, and
 * (for CR2 only) the embedded JPEG preview from a photo file, using {@code metadata-extractor}.
 * See implementation plan §9 for the full rationale behind the capture-date priority ordering —
 * this class mirrors it exactly.
 * <p>
 * Everything else metadata-extractor finds (GPS, ISO, aperture, shutter speed, focal length,
 * white balance, maker notes, etc.) is not modeled individually here — it's dumped generically,
 * grouped by directory name, into a single JSON blob via {@link #buildExifExtraJson(Metadata)}.
 * See {@link #PROMOTED_TAGS} for the handful of tags excluded from that dump because they're
 * already covered by a dedicated field above.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoMetadataExtractor {

    private static final Set<String> JPEG_EXTENSIONS = Set.of("jpg", "jpeg");
    private static final String PNG_EXTENSION = "png";
    private static final String CR2_EXTENSION = "cr2";

    /**
     * EXIF Orientation tag values (1-8) mapped to the clockwise rotation, in degrees, needed to
     * display the image correctly. Only the rotation component is tracked — mirrored values
     * (2,4,5,7) collapse onto their non-mirrored counterpart, since flips aren't supported.
     */
    private static final Map<Integer, Integer> ORIENTATION_TO_ROTATION_DEGREES = Map.of(
            1, 0,
            2, 0,
            3, 180,
            4, 180,
            5, 90,
            6, 90,
            7, 270,
            8, 270
    );

    /**
     * Tags excluded from the generic {@code exif_extra} dump because they're already surfaced as
     * a dedicated field: orientation, camera make/model, lens model, the capture-date tags (see
     * {@link #extractCaptureDate}), and the width/height tags (see {@link #extractDimensions}).
     * {@link ExifThumbnailDirectory} is skipped wholesale below rather than tag-by-tag — its
     * offset/length/compression tags describe the embedded preview file, not the photo.
     */
    private static final Set<DirectoryTagKey> PROMOTED_TAGS = Set.of(
            new DirectoryTagKey(ExifIFD0Directory.class, ExifIFD0Directory.TAG_ORIENTATION),
            new DirectoryTagKey(ExifIFD0Directory.class, ExifIFD0Directory.TAG_MAKE),
            new DirectoryTagKey(ExifIFD0Directory.class, ExifIFD0Directory.TAG_MODEL),
            new DirectoryTagKey(ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_LENS_MODEL),
            new DirectoryTagKey(ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL),
            new DirectoryTagKey(ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED),
            new DirectoryTagKey(ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH),
            new DirectoryTagKey(ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT),
            new DirectoryTagKey(JpegDirectory.class, JpegDirectory.TAG_IMAGE_WIDTH),
            new DirectoryTagKey(JpegDirectory.class, JpegDirectory.TAG_IMAGE_HEIGHT),
            new DirectoryTagKey(PngDirectory.class, PngDirectory.TAG_IMAGE_WIDTH),
            new DirectoryTagKey(PngDirectory.class, PngDirectory.TAG_IMAGE_HEIGHT)
    );

    private final ObjectMapper objectMapper;

    /**
     * @param file      absolute path to the image file on disk
     * @param extension lower-cased file extension without the dot (e.g. {@code "jpg"}), used to
     *                  decide which format-specific fallback directories are worth checking
     */
    public ExtractedMetadata extract(Path file, String extension) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (ImageProcessingException | IOException e) {
            // metadata-extractor couldn't even open/parse the file (corrupt or unsupported)
            // — still produce a usable result via the filesystem-only fallback rather than
            // failing the whole import for this one file (per-file isolation, see ImportService).
            log.warn("metadata-extractor could not read {}, falling back to filesystem date only", file, e);
            return new ExtractedMetadata(null, null, fileSystemDate(file), CaptureDateSource.FILESYSTEM,
                    0, null, null, null, null);
        }

        CaptureDateAndSource captureDateAndSource = extractCaptureDate(metadata, file);
        Dimensions dimensions = extractDimensions(metadata, extension, file);

        return new ExtractedMetadata(dimensions.width(), dimensions.height(),
                captureDateAndSource.date(), captureDateAndSource.source(),
                extractOrientationDegrees(metadata),
                extractCameraMake(metadata), extractCameraModel(metadata), extractLensModel(metadata),
                buildExifExtraJson(metadata));
    }

    /**
     * Extracts the embedded JPEG preview from a TIFF-based file (CR2). Used both as a thumbnail
     * source ({@code ThumbnailGenerator}) and, as a last resort, a width/height source above.
     * <p>
     * <b>Known regression:</b> {@code ExifThumbnailDirectory.hasThumbnailData()} has returned
     * {@code false} for some CR2 files on metadata-extractor 2.8.1+ even though the thumbnail
     * tags are present (drewnoakes/metadata-extractor#149). The manual-offset fallback below
     * exists specifically to cover that case. Verify both paths against a real CR2 sample with
     * the exact pinned library version before relying on this in production — see the README in
     * {@code src/test/resources/fixtures}.
     */
    public Optional<byte[]> extractEmbeddedPreviewBytes(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifThumbnailDirectory thumbnailDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
            if (thumbnailDirectory == null) {
                return Optional.empty();
            }

            if (thumbnailDirectory.containsTag(ExifThumbnailDirectory.TAG_COMPRESSION)
                    && thumbnailDirectory.containsTag(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET)
                    && thumbnailDirectory.containsTag(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH)) {
                Integer offset = thumbnailDirectory.getInteger(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET);
                Integer length = thumbnailDirectory.getInteger(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH);
                if (offset != null && length != null && length > 0) {
                    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                        byte[] buffer = new byte[length];
                        raf.seek(offset);
                        raf.readFully(buffer);
                        return Optional.of(buffer);
                    }
                }
            }

            return Optional.empty();
        } catch (ImageProcessingException | IOException e) {
            log.warn("Failed to extract embedded preview from {}", file, e);
            return Optional.empty();
        }
    }

    private record CaptureDateAndSource(LocalDateTime date, CaptureDateSource source) {
    }

    private CaptureDateAndSource extractCaptureDate(Metadata metadata, Path file) {
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (subIfd != null) {
            Date original = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (original != null) {
                return new CaptureDateAndSource(toLocalDateTime(original), CaptureDateSource.EXIF_ORIGINAL);
            }
            Date digitized = subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED);
            if (digitized != null) {
                return new CaptureDateAndSource(toLocalDateTime(digitized), CaptureDateSource.EXIF_DIGITIZED);
            }
        }
        return new CaptureDateAndSource(fileSystemDate(file), CaptureDateSource.FILESYSTEM);
    }

    private record Dimensions(Integer width, Integer height) {
        private static final Dimensions EMPTY = new Dimensions(null, null);

        private boolean isComplete() {
            return width != null && height != null;
        }
    }

    private Dimensions extractDimensions(Metadata metadata, String extension, Path file) {
        Dimensions dimensions = fromExifSubIfd(metadata);
        if (dimensions.isComplete()) {
            return dimensions;
        }

        if (JPEG_EXTENSIONS.contains(extension)) {
            dimensions = preferExisting(dimensions, fromJpegDirectory(metadata));
        } else if (PNG_EXTENSION.equals(extension)) {
            dimensions = preferExisting(dimensions, fromPngDirectory(metadata));
        }
        if (dimensions.isComplete()) {
            return dimensions;
        }

        if (CR2_EXTENSION.equals(extension)) {
            dimensions = preferExisting(dimensions, fromEmbeddedPreview(file));
        }
        return dimensions;
    }

    private Dimensions fromExifSubIfd(Metadata metadata) {
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (subIfd == null) {
            return Dimensions.EMPTY;
        }
        return new Dimensions(
                subIfd.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH),
                subIfd.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT));
    }

    private Dimensions fromJpegDirectory(Metadata metadata) {
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg == null) {
            return Dimensions.EMPTY;
        }
        return new Dimensions(
                jpeg.getInteger(JpegDirectory.TAG_IMAGE_WIDTH),
                jpeg.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT));
    }

    private Dimensions fromPngDirectory(Metadata metadata) {
        PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
        if (png == null) {
            return Dimensions.EMPTY;
        }
        return new Dimensions(
                png.getInteger(PngDirectory.TAG_IMAGE_WIDTH),
                png.getInteger(PngDirectory.TAG_IMAGE_HEIGHT));
    }

    private Dimensions fromEmbeddedPreview(Path file) {
        return extractEmbeddedPreviewBytes(file)
                .map(this::decodeDimensions)
                .orElse(Dimensions.EMPTY);
    }

    private Dimensions decodeDimensions(byte[] previewBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(previewBytes));
            return image == null ? Dimensions.EMPTY : new Dimensions(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            return Dimensions.EMPTY;
        }
    }

    /**
     * Fills in only the still-missing half (width or height) from a fallback source.
     */
    private Dimensions preferExisting(Dimensions existing, Dimensions fallback) {
        return new Dimensions(
                existing.width() != null ? existing.width() : fallback.width(),
                existing.height() != null ? existing.height() : fallback.height());
    }

    /**
     * Maps the EXIF Orientation tag (read from {@link ExifIFD0Directory}, where it normally
     * lives) to a clockwise rotation in degrees. Defaults to {@code 0} when the tag is absent,
     * unreadable, or holds a value outside 1-8.
     */
    private int extractOrientationDegrees(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (ifd0 == null || !ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return 0;
        }
        Integer raw = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        return raw == null ? 0 : ORIENTATION_TO_ROTATION_DEGREES.getOrDefault(raw, 0);
    }

    private String extractCameraMake(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        return ifd0 == null ? null : trimToNull(ifd0.getString(ExifIFD0Directory.TAG_MAKE));
    }

    private String extractCameraModel(Metadata metadata) {
        ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        return ifd0 == null ? null : trimToNull(ifd0.getString(ExifIFD0Directory.TAG_MODEL));
    }

    private String extractLensModel(Metadata metadata) {
        ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        return subIfd == null ? null : trimToNull(subIfd.getString(ExifSubIFDDirectory.TAG_LENS_MODEL));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record DirectoryTagKey(Class<? extends Directory> directoryClass, int tagType) {
    }

    /**
     * Generic dump of every tag metadata-extractor found, across every directory, except the
     * handful already promoted to a dedicated field (see {@link #PROMOTED_TAGS}) and the whole of
     * {@link ExifThumbnailDirectory} (internal preview plumbing, not photo metadata). Grouped by
     * directory name — e.g. {@code "GPS"}, {@code "Exif SubIFD"}, {@code "Canon Makernote"} — so
     * the resulting JSON stays readable rather than one flat bag of tag names. Returns
     * {@code null} (not an empty object) when nothing is left to dump, e.g. a PNG with no
     * embedded EXIF at all.
     */
    private String buildExifExtraJson(Metadata metadata) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (Directory directory : metadata.getDirectories()) {
            if (directory instanceof ExifThumbnailDirectory) {
                continue;
            }
            for (Tag tag : directory.getTags()) {
                if (PROMOTED_TAGS.contains(new DirectoryTagKey(directory.getClass(), tag.getTagType()))) {
                    continue;
                }
                String description = tag.getDescription();
                if (description == null) {
                    continue;
                }
                grouped.computeIfAbsent(directory.getName(), k -> new LinkedHashMap<>())
                        .put(tag.getTagName(), description);
            }
        }
        if (grouped.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(grouped);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize EXIF extras to JSON for {}", metadata, e);
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private LocalDateTime fileSystemDate(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            log.warn("Could not read filesystem modified time for {}", file, e);
            return null;
        }
    }
}
