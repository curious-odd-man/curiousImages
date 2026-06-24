package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoRepositoryTest extends AbstractRepositoryH2Test {

    private long aFolderId() {
        long importRootId = new ImportRootRepository(dsl).findOrCreate("D:\\Photos", LocalDateTime.now());
        return new FolderRepository(dsl).findOrCreate(importRootId, null, "", "Photos");
    }

    @Test
    void findByAbsolutePathReturnsEmptyWhenNotImportedYet() {
        PhotoRepository repository = new PhotoRepository(dsl);

        assertTrue(repository.findByAbsolutePath("D:\\Photos\\a.jpg")
                             .isEmpty());
    }

    @Test
    void insertThenFindByAbsolutePathRoundTrips() {
        PhotoRepository repository  = new PhotoRepository(dsl);
        long            folderId    = aFolderId();
        LocalDateTime   now         = LocalDateTime.of(2024, 6, 1, 12, 0);
        LocalDateTime   captureDate = LocalDateTime.of(2023, 6, 15, 14, 30);

        long photoId = repository.insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg",
                12345L, 800, 600, captureDate, CaptureDateSource.EXIF_ORIGINAL, 0, "", "", "", "", now);

        Optional<?> found = repository.findByAbsolutePath("D:\\Photos\\a.jpg");
        assertTrue(found.isPresent());

        var record = dsl.selectFrom(PHOTO)
                        .where(PHOTO.ID.eq(photoId))
                        .fetchOne();
        assertNotNull(record);
        assertEquals("a.jpg", record.getFilename());
        assertEquals("jpg", record.getExtension());
        assertEquals(12345L, record.getFileSize());
        assertEquals(800, record.getImageWidth());
        assertEquals(600, record.getImageHeight());
        assertEquals(captureDate, record.getCaptureDate());
        assertEquals("EXIF_ORIGINAL", record.getCaptureDateSource());
        assertEquals(now, record.getImportedAt());
        assertEquals(now, record.getLastSeenAt());
    }

    @Test
    void absolutePathIsUniqueAcrossInserts() {
        PhotoRepository repository = new PhotoRepository(dsl);
        long            folderId   = aFolderId();
        LocalDateTime   now        = LocalDateTime.now();

        repository.insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg", 100L, 1, 1, null, null, 0, "", "", "", "", now);

        assertThrows(Exception.class, () ->
                repository.insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg", 200L, 1, 1, null, null, 0, "", "", "", "", now));
    }

    @Test
    void touchLastSeenAtQueryOnlyUpdatesThatColumn() {
        PhotoRepository repository = new PhotoRepository(dsl);
        long            folderId   = aFolderId();
        LocalDateTime   importedAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        long photoId = repository.insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg",
                100L, 800, 600, null, null, 0, "", "", "", "", importedAt);

        LocalDateTime rescannedAt = LocalDateTime.of(2024, 6, 1, 0, 0);
        repository.touchLastSeenAtQuery(photoId, rescannedAt)
                  .execute();

        var record = dsl.selectFrom(PHOTO)
                        .where(PHOTO.ID.eq(photoId))
                        .fetchOne();
        assertEquals(rescannedAt, record.getLastSeenAt());
        assertEquals(100L, record.getFileSize(), "unrelated columns must be untouched");
        assertEquals(importedAt, record.getImportedAt());
    }

    @Test
    void updateMetadataQueryUpdatesFileSizeDimensionsAndCaptureDate() {
        PhotoRepository repository = new PhotoRepository(dsl);
        long            folderId   = aFolderId();
        long photoId = repository.insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg",
                100L, 800, 600, null, null, 0, "", "", "", "", LocalDateTime.now());

        LocalDateTime now            = LocalDateTime.of(2024, 6, 1, 0, 0);
        LocalDateTime newCaptureDate = LocalDateTime.of(2024, 5, 1, 9, 0);
        repository.updateMetadataQuery(photoId, 999L, 1024, 768, newCaptureDate,
                          CaptureDateSource.FILESYSTEM, 0, "", "", "", "", now)
                  .execute();

        var record = dsl.selectFrom(PHOTO)
                        .where(PHOTO.ID.eq(photoId))
                        .fetchOne();
        assertEquals(999L, record.getFileSize());
        assertEquals(1024, record.getImageWidth());
        assertEquals(768, record.getImageHeight());
        assertEquals(newCaptureDate, record.getCaptureDate());
        assertEquals("FILESYSTEM", record.getCaptureDateSource());
        assertEquals(now, record.getLastSeenAt());
    }
}
