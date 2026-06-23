package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /**
     * Deletes a thumbnail row for a photo being removed, used when resolving duplicates. Caller
     * passes a {@code ctx} bound to the same transaction as the {@code PHOTO} row delete — see
     * {@code DuplicateResolutionService}.
     */
    public void deleteByPhotoId(DSLContext ctx, long photoId) {
        ctx.deleteFrom(THUMBNAIL)
           .where(THUMBNAIL.PHOTO_ID.eq(photoId))
           .execute();
    }

    public Optional<ThumbnailRecord> findByPhotoId(long photoId) {
        return Optional.ofNullable(
                dsl.selectFrom(THUMBNAIL)
                   .where(THUMBNAIL.PHOTO_ID.eq(photoId))
                   .fetchOne());
    }

    /**
     * Batch lookup for a page/folder of photos — avoids one query per photo when populating the
     * grid. Photos with no thumbnail row simply have no entry in the returned map.
     */
    public Map<Long, ThumbnailRecord> findByPhotoIds(Collection<Long> photoIds) {
        if (photoIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(THUMBNAIL)
                  .where(THUMBNAIL.PHOTO_ID.in(photoIds))
                  .fetch()
                  .stream()
                  .collect(Collectors.toMap(ThumbnailRecord::getPhotoId, r -> r));
    }
}
