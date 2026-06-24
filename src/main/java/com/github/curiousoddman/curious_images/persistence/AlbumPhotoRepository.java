package com.github.curiousoddman.curious_images.persistence;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.ALBUM_PHOTO;

/**
 * Hand-written jOOQ repository for {@code album_photo}.
 * All writes return unexecuted {@link Query} objects for caller-controlled batching.
 */
@Repository
@RequiredArgsConstructor
public class AlbumPhotoRepository {

    private final DSLContext dsl;

    /**
     * Returns an unexecuted INSERT for a single album-photo membership row.
     * Queue into a buffer and flush as a batch.
     */
    public Query insertQuery(long albumId, long photoId, int sortOrder, LocalDateTime now) {
        return dsl.insertInto(ALBUM_PHOTO)
                  .set(ALBUM_PHOTO.ALBUM_ID, albumId)
                  .set(ALBUM_PHOTO.PHOTO_ID, photoId)
                  .set(ALBUM_PHOTO.SORT_ORDER, sortOrder)
                  .set(ALBUM_PHOTO.ADDED_AT, now);
    }

    public List<Long> findPhotoIdsByAlbumId(long albumId) {
        return dsl.select(ALBUM_PHOTO.PHOTO_ID)
                  .from(ALBUM_PHOTO)
                  .where(ALBUM_PHOTO.ALBUM_ID.eq(albumId))
                  .orderBy(ALBUM_PHOTO.SORT_ORDER)
                  .fetch(ALBUM_PHOTO.PHOTO_ID);
    }
}
