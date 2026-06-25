package com.github.curiousoddman.curious_images.domain.imports.thumbnail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.SourceImageDecoder;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailCachePaths;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailGeneratorTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @TempDir
    Path cacheDir;

    private ThumbnailGenerator generator;

    @BeforeEach
    void setUp() {
        ThumbnailCachePaths cachePaths = new ThumbnailCachePaths(cacheDir.toString());
        generator = new ThumbnailGenerator(cachePaths, new SourceImageDecoder(new PhotoMetadataExtractor(new ObjectMapper())));
    }

    @Test
    void landscapeImageIsConstrainedToLongestEdge512PreservingAspectRatio() throws IOException {
        // Fixture is 800x600 (4:3 landscape)
        Optional<ThumbnailGenerator.GeneratedThumbnail> result =
                generator.generate(FIXTURES.resolve("with-exif-dates.jpg"), "jpg", 0);

        assertTrue(result.isPresent());
        ThumbnailGenerator.GeneratedThumbnail thumbnail = result.get();
        assertEquals(512, thumbnail.width(), "longest edge (width, for landscape) must be 512");
        assertEquals(384, thumbnail.height(), "height must scale to preserve the 4:3 aspect ratio");

        BufferedImage written = ImageIO.read(cacheDir.resolve(thumbnail.cachePath())
                                                     .toFile());
        assertEquals(512, written.getWidth());
        assertEquals(384, written.getHeight());
    }

    @Test
    void portraitImageIsConstrainedToLongestEdge512PreservingAspectRatio() {
        // Fixture is 600x800 (3:4 portrait)
        Optional<ThumbnailGenerator.GeneratedThumbnail> result =
                generator.generate(FIXTURES.resolve("portrait.jpg"), "jpg", 0);

        assertTrue(result.isPresent());
        ThumbnailGenerator.GeneratedThumbnail thumbnail = result.get();
        assertEquals(384, thumbnail.width(), "width must scale to preserve the 3:4 aspect ratio");
        assertEquals(512, thumbnail.height(), "longest edge (height, for portrait) must be 512");
    }

    @Test
    void shardPathIsDeterministicAndReproducibleForAGivenPhotoId() {
        Optional<ThumbnailGenerator.GeneratedThumbnail> first =
                generator.generate(FIXTURES.resolve("with-exif-dates.jpg"), "jpg", 0);
        Optional<ThumbnailGenerator.GeneratedThumbnail> second =
                generator.generate(FIXTURES.resolve("with-exif-dates.jpg"), "jpg", 0);

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(first.get()
                          .cachePath(), second.get()
                                              .cachePath());
        assertEquals("345/12345.jpg", first.get()
                                           .cachePath());
    }

    @Test
    void ensureThumbnailRegeneratesOnlyWhenMissingFromDisk() {
        Path source = FIXTURES.resolve("with-exif-dates.jpg");

        boolean firstCall = generator.ensureThumbnail(source, "jpg", 0);
        assertTrue(firstCall, "should regenerate because nothing exists on disk yet");

        boolean secondCall = generator.ensureThumbnail(source, "jpg", 0);
        assertFalse(secondCall, "should be a no-op once the cache file already exists");
    }

    @Test
    void unsupportedOrUndecodableSourceProducesNoThumbnailRatherThanThrowing() {
        Optional<ThumbnailGenerator.GeneratedThumbnail> result =
                generator.generate(FIXTURES.resolve("README.md"), "jpg", 0);

        assertTrue(result.isEmpty());
    }
}
