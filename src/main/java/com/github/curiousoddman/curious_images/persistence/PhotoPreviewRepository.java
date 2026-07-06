package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoPreviewRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO_PREVIEW;

/**
 * Hand-written jOOQ repository for {@code photo_preview} — the embedded EXIF preview bytes
 * extracted for free during the Phase 1 metadata scan (JPEG only, see
 * {@code PhotoMetadataExtractor}), used as an instant "quick preview" while the real on-demand
 * thumbnail is generated in the background. See implementation plan "Instant quick preview"
 * section. {@code photo_id} is the primary key (1:1 with {@code photo}), so a write is always an
 * upsert, mirroring {@link ThumbnailRepository}.
 */
@Repository
@RequiredArgsConstructor
public class PhotoPreviewRepository {
    private final DSLContext dsl;

    /**
     * Returns an unexecuted upsert {@link Query} — piggybacked onto {@code ImportJob}'s existing
     * batched flush so it costs no extra disk writes.
     */
    public Query upsertQuery(long photoId, byte[] bytes) {
        return dsl.mergeInto(PHOTO_PREVIEW)
                  .using(dsl.selectOne())
                  .on(PHOTO_PREVIEW.PHOTO_ID.eq(photoId))
                  .whenMatchedThenUpdate()
                  .set(PHOTO_PREVIEW.BYTES, bytes)
                  .whenNotMatchedThenInsert(PHOTO_PREVIEW.PHOTO_ID, PHOTO_PREVIEW.BYTES)
                  .values(photoId, bytes);
    }

    /**
     * Batch lookup for a page of photos — mirrors {@link ThumbnailRepository#findByPhotoIds}.
     * Photos with no embedded preview (PNG, CR2, corrupt files) simply have no entry.
     */
    public Map<Long, PhotoPreviewRecord> findByPhotoIds(Collection<Long> photoIds) {
        if (photoIds.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(PHOTO_PREVIEW)
                  .where(PHOTO_PREVIEW.PHOTO_ID.in(photoIds))
                  .fetch()
                  .stream()
                  .collect(Collectors.toMap(PhotoPreviewRecord::getPhotoId, r -> r));
    }
}
