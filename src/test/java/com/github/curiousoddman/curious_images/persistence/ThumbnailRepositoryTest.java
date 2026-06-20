package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailRepositoryTest extends AbstractRepositoryH2Test {

    private long aPhotoId() {
        long importRootId = new ImportRootRepository(dsl).findOrCreate("D:\\Photos", LocalDateTime.now());
        long folderId = new FolderRepository(dsl).findOrCreate(importRootId, null, "", "Photos");
        return new PhotoRepository(dsl).insert(folderId, "D:\\Photos\\a.jpg", "a.jpg", "jpg",
                100L, 800, 600, null, CaptureDateSource.FILESYSTEM, LocalDateTime.now());
    }

    @Test
    void upsertQueryInsertsWhenNoThumbnailExistsYet() {
        ThumbnailRepository repository = new ThumbnailRepository(dsl);
        long photoId = aPhotoId();
        LocalDateTime now = LocalDateTime.of(2024, 6, 1, 0, 0);

        repository.upsertQuery(photoId, "0/" + photoId + ".jpg", 512, 384, now).execute();

        var found = repository.findByPhotoId(photoId);
        assertTrue(found.isPresent());
        assertEquals(512, found.get().getWidth());
        assertEquals(384, found.get().getHeight());
    }

    @Test
    void upsertQueryUpdatesExistingThumbnailRow() {
        ThumbnailRepository repository = new ThumbnailRepository(dsl);
        long photoId = aPhotoId();
        repository.upsertQuery(photoId, "0/" + photoId + ".jpg", 512, 384, LocalDateTime.of(2024, 1, 1, 0, 0)).execute();

        LocalDateTime regeneratedAt = LocalDateTime.of(2024, 6, 1, 0, 0);
        repository.upsertQuery(photoId, "0/" + photoId + ".jpg", 256, 192, regeneratedAt).execute();

        var found = repository.findByPhotoId(photoId);
        assertTrue(found.isPresent());
        assertEquals(256, found.get().getWidth());
        assertEquals(192, found.get().getHeight());
        assertEquals(regeneratedAt, found.get().getGeneratedAt());
    }

    @Test
    void findByPhotoIdReturnsEmptyWhenNoThumbnailGenerated() {
        ThumbnailRepository repository = new ThumbnailRepository(dsl);
        long photoId = aPhotoId();

        assertTrue(repository.findByPhotoId(photoId).isEmpty());
    }
}
