package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailCachePaths;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.event.RescanLibraryEvent;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.test.H2TestDatabase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.FOLDER;
import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.THUMBNAIL;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class ImportServiceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @TempDir
    Path libraryRoot;

    @TempDir
    Path thumbnailCacheDir;

    private RecordingEventPublisher eventPublisher;
    private DSLContext              dsl;
    private FakeTimeProvider        timeProvider;
    private ImportService           importService;

    private void setUp(PhotoMetadataExtractor metadataExtractor) {
        eventPublisher = new RecordingEventPublisher();
        dsl = H2TestDatabase.freshMigratedDatabase();
        timeProvider = new FakeTimeProvider();

        ThumbnailCachePaths cachePaths         = new ThumbnailCachePaths(thumbnailCacheDir.toString());
        ThumbnailGenerator  thumbnailGenerator = new ThumbnailGenerator(cachePaths, metadataExtractor);

        importService = new ImportService(
                eventPublisher,
                dsl,
                new ImportRootRepository(dsl),
                new FolderRepository(dsl),
                new PhotoRepository(dsl),
                new ThumbnailRepository(dsl),
                metadataExtractor,
                thumbnailGenerator,
                timeProvider);
    }

    /**
     * a.jpg, b.png directly under the root, sub/c.jpg one level down, plus an ignored sidecar file.
     */
    private void populateLibraryWithThreeSupportedFilesAndOneSidecar() throws IOException {
        Files.copy(FIXTURES.resolve("with-exif-dates.jpg"), libraryRoot.resolve("a.jpg"));
        Files.copy(FIXTURES.resolve("plain.png"), libraryRoot.resolve("b.png"));
        Path sub = Files.createDirectories(libraryRoot.resolve("sub"));
        Files.copy(FIXTURES.resolve("no-exif-dates.jpg"), sub.resolve("c.jpg"));
        Files.writeString(libraryRoot.resolve("notes.txt"), "not a photo, must be ignored by extension filter");
    }

    private void awaitTerminalEvent(int expectedEndedCount) {
        await().atMost(Duration.ofSeconds(15))
               .untilAsserted(() -> {
                   long endedCount = eventPublisher.backgroundProcessEvents()
                                                   .stream()
                                                   .filter(e -> e.getEventType() == BackgroundProcessEventType.ENDED)
                                                   .count();
                   assertEquals(expectedEndedCount, endedCount);
               });
    }

    @Test
    void importsAllSupportedFilesIgnoresSidecarsAndPublishesTerminalEndedEvent() throws IOException {
        setUp(new PhotoMetadataExtractor());
        populateLibraryWithThreeSupportedFilesAndOneSidecar();

        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));
        awaitTerminalEvent(1);

        List<PhotoRecord> photos = dsl.selectFrom(PHOTO)
                                      .fetch();
        assertEquals(3, photos.size(), "the .txt sidecar must be ignored by the extension filter");

        // root folder ("") + "sub" = 2 folder rows, no duplicates.
        assertEquals(2, dsl.fetchCount(FOLDER));

        // All three fixtures are real, decodable images -> all three get a thumbnail.
        assertEquals(3, dsl.fetchCount(THUMBNAIL));

        assertTrue(eventPublisher.backgroundProcessEvents()
                                 .stream()
                                 .anyMatch(e -> e.getEventType() == BackgroundProcessEventType.STARTED));
    }

    @Test
    void rescanningAnUnchangedLibraryIsANoOpThatOnlyTouchesLastSeenAt() throws IOException {
        setUp(new PhotoMetadataExtractor());
        populateLibraryWithThreeSupportedFilesAndOneSidecar();

        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));
        awaitTerminalEvent(1);

        Map<String, java.time.LocalDateTime> lastSeenAfterFirstRun = dsl.selectFrom(PHOTO)
                                                                        .fetch()
                                                                        .stream()
                                                                        .collect(java.util.stream.Collectors.toMap(PhotoRecord::getAbsolutePath, PhotoRecord::getLastSeenAt));
        int photoCountAfterFirstRun = dsl.fetchCount(PHOTO);

        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));
        awaitTerminalEvent(2);

        assertEquals(photoCountAfterFirstRun, dsl.fetchCount(PHOTO), "rescan of an unchanged library must not duplicate rows");

        for (PhotoRecord photo : dsl.selectFrom(PHOTO)
                                    .fetch()) {
            java.time.LocalDateTime before = lastSeenAfterFirstRun.get(photo.getAbsolutePath());
            assertNotNull(before);
            assertTrue(photo.getLastSeenAt()
                            .isAfter(before),
                    "last_seen_at must advance on rescan even when the file itself is unchanged");
        }
    }

    @Test
    void aSingleFileFailureDoesNotAbortTheRestOfTheImport() throws IOException {
        PhotoMetadataExtractor realExtractor  = new PhotoMetadataExtractor();
        PhotoMetadataExtractor flakyExtractor = spy(realExtractor);
        Path                   corruptFile    = libraryRoot.resolve("corrupt.jpg");

        setUp(flakyExtractor);
        populateLibraryWithThreeSupportedFilesAndOneSidecar();
        Files.writeString(corruptFile, "deliberately not a real image, forced to throw below");

        // metadata-extractor itself fails gracefully (see PhotoMetadataExtractorTest), so to
        // exercise the per-file isolation try/catch in ImportService we force a hard failure for
        // this one file specifically, simulating e.g. an I/O error reading it mid-scan.
        doThrow(new RuntimeException("simulated unreadable file"))
                .when(flakyExtractor)
                .extract(eq(corruptFile), any());

        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));
        awaitTerminalEvent(1);

        assertEquals(3, dsl.fetchCount(PHOTO), "the 3 healthy files must still be imported");
        assertTrue(dsl.selectFrom(PHOTO)
                      .where(PHOTO.ABSOLUTE_PATH.eq(corruptFile.toAbsolutePath()
                                                               .toString()))
                      .fetchOptional()
                      .isEmpty(), "the file that threw must not have a partial row");
    }

    @Test
    void aSecondRescanEventWhileOneIsRunningIsIgnoredNotConcurrentlyStarted() throws IOException {
        setUp(new PhotoMetadataExtractor());
        populateLibraryWithThreeSupportedFilesAndOneSidecar();

        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));
        // Fired immediately after — the re-entrancy guard (AtomicBoolean running) must reject
        // this one outright rather than starting a second concurrent scan thread (see plan §8,
        // bug #2 fixed from the original FilesScanningService stub).
        importService.onRescanEvent(new RescanLibraryEvent(this, libraryRoot.toString()));

        awaitTerminalEvent(1);
        // Give a moment to make sure a second ENDED never shows up either.
        await().pollDelay(Duration.ofMillis(300))
               .atMost(Duration.ofSeconds(2))
               .until(() -> true);
        long endedCount = eventPublisher.backgroundProcessEvents()
                                        .stream()
                                        .filter(e -> e.getEventType() == BackgroundProcessEventType.ENDED)
                                        .count();
        assertEquals(1, endedCount);
    }
}
