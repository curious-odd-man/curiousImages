package com.github.curiousoddman.curious_images.domain.imports.metadata;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifThumbnailDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
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
import java.util.Optional;
import java.util.Set;

/**
 * Extracts capture date, full-resolution width/height, and (for CR2 only) the embedded JPEG
 * preview from a photo file, using {@code metadata-extractor}. See implementation plan §9 for the
 * full rationale behind the priority ordering below — this class mirrors it exactly.
 */
@Slf4j
@Component
public class PhotoMetadataExtractor {

    private static final Set<String> JPEG_EXTENSIONS = Set.of("jpg", "jpeg");
    private static final String PNG_EXTENSION = "png";
    private static final String CR2_EXTENSION = "cr2";

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
            return new ExtractedMetadata(null, null, fileSystemDate(file), CaptureDateSource.FILESYSTEM);
        }

        CaptureDateAndSource captureDateAndSource = extractCaptureDate(metadata, file);
        Dimensions dimensions = extractDimensions(metadata, extension, file);

        return new ExtractedMetadata(dimensions.width(), dimensions.height(),
                captureDateAndSource.date(), captureDateAndSource.source());
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
