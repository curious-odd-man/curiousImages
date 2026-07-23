package com.github.curiousoddman.curious_images.persistence;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.ALBUM_MEDIA;

/**
 * Hand-written jOOQ repository for {@code ALBUM_MEDIA}.
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
        return dsl.insertInto(ALBUM_MEDIA)
                  .set(ALBUM_MEDIA.ALBUM_ID, albumId)
                  .set(ALBUM_MEDIA.MEDIA_ID, photoId)
                  .set(ALBUM_MEDIA.SORT_ORDER, sortOrder)
                  .set(ALBUM_MEDIA.ADDED_AT, now);
    }

    public List<Long> findPhotoIdsByAlbumId(long albumId) {
        return dsl.select(ALBUM_MEDIA.MEDIA_ID)
                  .from(ALBUM_MEDIA)
                  .where(ALBUM_MEDIA.ALBUM_ID.eq(albumId))
                  .orderBy(ALBUM_MEDIA.SORT_ORDER)
                  .fetch(ALBUM_MEDIA.MEDIA_ID);
    }
}
