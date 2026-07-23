package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaHashRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_HASH;

/**
 * NOTE: written without sight of the project's existing repository classes (PhotoRepository,
 * ThumbnailRepository, etc.), so method shapes are inferred from how those are called in
 * ImportService — a buffered {@link Query} returned for batched writes, immediate execution when
 * the caller needs something back. Adjust to match your actual conventions if they differ.
 * <p>
 * Requires the PHOTO_HASH jOOQ table/record classes, generated from migration V004.
 */
@Repository
@RequiredArgsConstructor
public class MediaHashRepository {
    private final DSLContext dsl;

    /**
     * Loads every existing PHOTO_HASH row, keyed by photo_id. Called once per duplicate-detection
     * run to decide, per media, whether the cached hash can be reused — a single bulk query
     * rather than one lookup per media, since this runs against up to 25,000 rows.
     */
    public Map<Long, MediaHashRecord> findAllAsMap() {
        return dsl.selectFrom(MEDIA_HASH)
                  .fetch()
                  .stream()
                  .collect(Collectors.toMap(MediaHashRecord::getMediaId, r -> r));
    }

    public Query upsertQuery(long photoId, String pixelHash, long hashedFileSize, LocalDateTime now) {
        return dsl.insertInto(MEDIA_HASH)
                  .set(MEDIA_HASH.MEDIA_ID, photoId)
                  .set(MEDIA_HASH.CONTENT_HASH, pixelHash)
                  .set(MEDIA_HASH.HASHED_FILE_SIZE, hashedFileSize)
                  .set(MEDIA_HASH.HASHED_AT, now)
                  .onDuplicateKeyUpdate()
                  .set(MEDIA_HASH.CONTENT_HASH, pixelHash)
                  .set(MEDIA_HASH.HASHED_FILE_SIZE, hashedFileSize)
                  .set(MEDIA_HASH.HASHED_AT, now);
    }
}
