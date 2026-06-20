package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;

/**
 * Hand-written jOOQ repository for {@code photo}. {@code absolute_path} is {@code UNIQUE} — see
 * implementation plan §12 for the full idempotent-rescan rationale this repository supports.
 * <p>
 * {@link #insert} is the one write that always happens immediately (not batched): it's the only
 * write in the pipeline that needs a freshly generated {@code id} back before the corresponding
 * {@code THUMBNAIL} row can be queued. Every other write returns an unexecuted {@link Query} so
 * {@code ImportService} can buffer and flush it via {@code DSLContext#batch(...)} — see §13.
 */
@Repository
@RequiredArgsConstructor
public class PhotoRepository {
    private final DSLContext dsl;

    public Optional<PhotoRecord> findByAbsolutePath(String absolutePath) {
        return Optional.ofNullable(
                dsl.selectFrom(PHOTO)
                        .where(PHOTO.ABSOLUTE_PATH.eq(absolutePath))
                        .fetchOne());
    }

    public List<PhotoRecord> findByFolderId(long folderId) {
        return dsl.selectFrom(PHOTO)
                .where(PHOTO.FOLDER_ID.eq(folderId))
                .orderBy(PHOTO.FILENAME)
                .fetch();
    }

    public long insert(long folderId, String absolutePath, String filename, String extension,
                       long fileSize, Integer width, Integer height,
                       LocalDateTime captureDate, CaptureDateSource captureDateSource,
                       LocalDateTime now) {
        return dsl.insertInto(PHOTO)
                .set(PHOTO.FOLDER_ID, folderId)
                .set(PHOTO.ABSOLUTE_PATH, absolutePath)
                .set(PHOTO.FILENAME, filename)
                .set(PHOTO.EXTENSION, extension)
                .set(PHOTO.FILE_SIZE, fileSize)
                .set(PHOTO.IMAGE_WIDTH, width)
                .set(PHOTO.IMAGE_HEIGHT, height)
                .set(PHOTO.CAPTURE_DATE, captureDate)
                .set(PHOTO.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                .set(PHOTO.IMPORTED_AT, now)
                .set(PHOTO.LAST_SEEN_AT, now)
                .returning(PHOTO.ID)
                .fetchOne()
                .getId();
    }

    /**
     * Cheap rescan path (§12): file unchanged — just bump {@code last_seen_at}, queued for batching.
     */
    public Query touchLastSeenAtQuery(long photoId, LocalDateTime now) {
        return dsl.update(PHOTO)
                .set(PHOTO.LAST_SEEN_AT, now)
                .where(PHOTO.ID.eq(photoId));
    }

    /**
     * Modified-file rescan path (§12): file size changed — re-persist metadata, queued for batching.
     */
    public Query updateMetadataQuery(long photoId, long fileSize, Integer width, Integer height,
                                     LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                     LocalDateTime now) {
        return dsl.update(PHOTO)
                .set(PHOTO.FILE_SIZE, fileSize)
                .set(PHOTO.IMAGE_WIDTH, width)
                .set(PHOTO.IMAGE_HEIGHT, height)
                .set(PHOTO.CAPTURE_DATE, captureDate)
                .set(PHOTO.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                .set(PHOTO.LAST_SEEN_AT, now)
                .where(PHOTO.ID.eq(photoId));
    }

    private String sourceName(CaptureDateSource captureDateSource) {
        return captureDateSource == null ? null : captureDateSource.name();
    }
}
