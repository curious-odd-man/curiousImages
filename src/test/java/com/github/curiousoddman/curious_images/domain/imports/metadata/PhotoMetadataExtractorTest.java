package com.github.curiousoddman.curious_images.domain.imports.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoMetadataExtractorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    private final PhotoMetadataExtractor extractor = new PhotoMetadataExtractor(new ObjectMapper());

    @Test
    void prefersExifDateOriginalAndExifDimensionsWhenPresent() {
        ExtractedMetadata metadata = extractor.extract(FIXTURES.resolve("with-exif-dates.jpg"), "jpg");

        assertEquals(CaptureDateSource.EXIF_ORIGINAL, metadata.captureDateSource());
        assertEquals(LocalDateTime.of(2023, 6, 15, 14, 30, 0), metadata.captureDate());
        assertEquals(800, metadata.width());
        assertEquals(600, metadata.height());
    }

    @Test
    void fallsBackToFilesystemDateAndJpegDimensionsWhenNoExifPresent() {
        Path file = FIXTURES.resolve("no-exif-dates.jpg");

        ExtractedMetadata metadata = extractor.extract(file, "jpg");

        assertEquals(CaptureDateSource.FILESYSTEM, metadata.captureDateSource());
        assertNotNull(metadata.captureDate());
        // No EXIF dimension tags on this fixture — falls back to the JpegDirectory SOF dimensions.
        assertEquals(640, metadata.width());
        assertEquals(480, metadata.height());
    }

    @Test
    void readsPngDimensionsFromIhdr() {
        ExtractedMetadata metadata = extractor.extract(FIXTURES.resolve("plain.png"), "png");

        assertEquals(CaptureDateSource.FILESYSTEM, metadata.captureDateSource());
        assertEquals(320, metadata.width());
        assertEquals(240, metadata.height());
    }

    @Test
    void unreadableFileFallsBackGracefullyInsteadOfThrowing() {
        // A file that exists but isn't a real image at all — metadata-extractor will fail to
        // parse it; extract() must still return a usable (filesystem-only) result rather than
        // propagating the exception, so a single corrupt file can never abort the whole import.
        Path notAnImage = FIXTURES.resolve("README.md");

        ExtractedMetadata metadata = extractor.extract(notAnImage, "jpg");

        assertEquals(CaptureDateSource.FILESYSTEM, metadata.captureDateSource());
        assertNull(metadata.width());
        assertNull(metadata.height());
    }

    @Test
    @Disabled("""
            No real CR2 sample is available in this sandbox (no network access to fetch one, and \
            no way to validate a hand-fabricated TIFF against the real metadata-extractor jar \
            offline). Drop a real CR2 file at src/test/resources/fixtures/sample.cr2 and enable \
            this test — see fixtures/README.md.""")
    void cr2EmbeddedPreviewExtraction() {
        Path cr2 = FIXTURES.resolve("sample.cr2");

        ExtractedMetadata metadata = extractor.extract(cr2, "cr2");
        assertNotNull(metadata.width());
        assertNotNull(metadata.height());

        var preview = extractor.extractEmbeddedPreviewBytes(cr2);
        assertTrue(preview.isPresent());
        assertTrue(preview.get().length > 0);
    }
}
