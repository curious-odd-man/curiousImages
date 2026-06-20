package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.THUMBNAIL;

/**
 * Hand-written jOOQ repository for {@code thumbnail}. {@code photo_id} is the primary key (1:1
 * with {@code photo}), so a thumbnail write is always an upsert.
 */
@Repository
@RequiredArgsConstructor
public class ThumbnailRepository {
    private final DSLContext dsl;

    /**
     * Returns an unexecuted upsert {@link Query} — queued by {@code ImportService} for batching.
     */
    public Query upsertQuery(long photoId, String cachePath, int width, int height, LocalDateTime now) {
        return dsl.mergeInto(THUMBNAIL)
                .using(dsl.selectOne())
                .on(THUMBNAIL.PHOTO_ID.eq(photoId))
                .whenMatchedThenUpdate()
                .set(THUMBNAIL.CACHE_PATH, cachePath)
                .set(THUMBNAIL.WIDTH, width)
                .set(THUMBNAIL.HEIGHT, height)
                .set(THUMBNAIL.GENERATED_AT, now)
                .whenNotMatchedThenInsert(THUMBNAIL.PHOTO_ID, THUMBNAIL.CACHE_PATH, THUMBNAIL.WIDTH,
                        THUMBNAIL.HEIGHT, THUMBNAIL.GENERATED_AT)
                .values(photoId, cachePath, width, height, now);
    }

    public Optional<ThumbnailRecord> findByPhotoId(long photoId) {
        return Optional.ofNullable(
                dsl.selectFrom(THUMBNAIL)
                        .where(THUMBNAIL.PHOTO_ID.eq(photoId))
                        .fetchOne());
    }
}
