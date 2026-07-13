package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoHashRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.dbobj.tables.PhotoHash.PHOTO_HASH;

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
public class PhotoHashRepository {
    private final DSLContext dsl;

    /**
     * Loads every existing PHOTO_HASH row, keyed by photo_id. Called once per duplicate-detection
     * run to decide, per photo, whether the cached hash can be reused — a single bulk query
     * rather than one lookup per photo, since this runs against up to 25,000 rows.
     */
    public Map<Long, PhotoHashRecord> findAllAsMap() {
        return dsl.selectFrom(PHOTO_HASH)
                  .fetch()
                  .stream()
                  .collect(Collectors.toMap(PhotoHashRecord::getPhotoId, r -> r));
    }

    public Query upsertQuery(long photoId, String pixelHash, long hashedFileSize, LocalDateTime now) {
        return dsl.insertInto(PHOTO_HASH)
                  .set(PHOTO_HASH.PHOTO_ID, photoId)
                  .set(PHOTO_HASH.PIXEL_HASH, pixelHash)
                  .set(PHOTO_HASH.HASHED_FILE_SIZE, hashedFileSize)
                  .set(PHOTO_HASH.HASHED_AT, now)
                  .onDuplicateKeyUpdate()
                  .set(PHOTO_HASH.PIXEL_HASH, pixelHash)
                  .set(PHOTO_HASH.HASHED_FILE_SIZE, hashedFileSize)
                  .set(PHOTO_HASH.HASHED_AT, now);
    }
}
