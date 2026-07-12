package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.PhotoForHashing;
import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
    private final DSLContext   dsl;
    private final TimeProvider timeProvider;

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
                       int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                       String exifExtraJson, LocalDateTime now) {
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
                  .set(PHOTO.ORIENTATION, orientationDegrees)
                  .set(PHOTO.CAMERA_MAKE, cameraMake)
                  .set(PHOTO.CAMERA_MODEL, cameraModel)
                  .set(PHOTO.LENS_MODEL, lensModel)
                  .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
                  .set(PHOTO.IMPORTED_AT, now)
                  .set(PHOTO.LAST_SEEN_AT, now)
                  .set(PHOTO.AI_UPDATED_AT, now)
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
                                     int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                                     String exifExtraJson, LocalDateTime now) {
        return dsl.update(PHOTO)
                  .set(PHOTO.FILE_SIZE, fileSize)
                  .set(PHOTO.IMAGE_WIDTH, width)
                  .set(PHOTO.IMAGE_HEIGHT, height)
                  .set(PHOTO.CAPTURE_DATE, captureDate)
                  .set(PHOTO.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                  .set(PHOTO.ORIENTATION, orientationDegrees)
                  .set(PHOTO.CAMERA_MAKE, cameraMake)
                  .set(PHOTO.CAMERA_MODEL, cameraModel)
                  .set(PHOTO.LENS_MODEL, lensModel)
                  .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
                  .set(PHOTO.LAST_SEEN_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    /**
     * Manual rotation correction (context-menu "Rotate" action, see {@code PhotoRotationService}):
     * overwrites the stored {@code ORIENTATION} and resets every AI "done" flag + clears the last
     * error/retry count, so the next {@code AiPipelineJob} run reprocesses this photo from scratch.
     * Executes immediately against the caller-supplied {@code ctx} — always called as one step of
     * {@code PhotoRotationService#rotate}'s single-photo transaction, never in bulk, mirroring the
     * {@code ctx}-taking delete methods elsewhere in this class/{@code ThumbnailRepository}/etc.
     */
    public void updateOrientationAndResetAi(DSLContext ctx, long photoId, int newOrientationDegrees, LocalDateTime now) {
        ctx.update(PHOTO)
           .set(PHOTO.ORIENTATION, newOrientationDegrees)
           .set(PHOTO.AI_FACE_DETECT_DONE, false)
           .set(PHOTO.AI_FACE_EMBED_DONE, false)
           .set(PHOTO.AI_CLIP_EMBED_DONE, false)
           .set(PHOTO.AI_LUCENE_INDEX_DONE, false)
           .set(PHOTO.AI_LAST_ERROR, (String) null)
           .set(PHOTO.AI_RETRY_COUNT, (short) 0)
           .set(PHOTO.AI_UPDATED_AT, now)
           .where(PHOTO.ID.eq(photoId))
           .execute();
    }

    /**
     * Deletes a single photo row, used when resolving duplicates. Caller is responsible for first
     * deleting dependent rows ({@code THUMBNAIL}, {@code DUPLICATE_GROUP_MEMBER}) and for passing
     * a {@code ctx} bound to the same transaction — see {@code DuplicateResolutionService}.
     */
    public void deleteById(DSLContext ctx, long photoId) {
        ctx.deleteFrom(PHOTO)
           .where(PHOTO.ID.eq(photoId))
           .execute();
    }

    private String sourceName(CaptureDateSource captureDateSource) {
        return captureDateSource == null ? null : captureDateSource.name();
    }

    private JSON toJson(String json) {
        return json == null ? null : JSON.valueOf(json);
    }

    public List<PhotoForHashing> findAllForHashing() {
        return dsl.select(PHOTO.ID, PHOTO.ABSOLUTE_PATH, PHOTO.EXTENSION, PHOTO.FILE_SIZE)
                  .from(PHOTO)
                  .fetch(r -> new PhotoForHashing(
                          r.get(PHOTO.ID), r.get(PHOTO.ABSOLUTE_PATH), r.get(PHOTO.EXTENSION), r.get(PHOTO.FILE_SIZE)));
    }

    // ── Timeline queries ──────────────────────────────────────────────────────────

    /**
     * Returns every distinct (year, month, day) that has at least one photo with a non-null
     * {@code capture_date}, together with a per-day photo count and the total count of photos
     * whose {@code capture_date} IS NULL — all in a single query via UNION ALL.
     */
    public TimelineData findTimelineData() {
        // Day-level counts
        var dayRows = dsl.select(
                                 DSL.year(PHOTO.CAPTURE_DATE)
                                    .as("yr"),
                                 DSL.month(PHOTO.CAPTURE_DATE)
                                    .as("mo"),
                                 DSL.day(PHOTO.CAPTURE_DATE)
                                    .as("dy"),
                                 DSL.count()
                                    .as("cnt"))
                         .from(PHOTO)
                         .where(PHOTO.CAPTURE_DATE.isNotNull())
                         .groupBy(DSL.year(PHOTO.CAPTURE_DATE),
                                 DSL.month(PHOTO.CAPTURE_DATE),
                                 DSL.day(PHOTO.CAPTURE_DATE))
                         .orderBy(DSL.year(PHOTO.CAPTURE_DATE),
                                 DSL.month(PHOTO.CAPTURE_DATE),
                                 DSL.day(PHOTO.CAPTURE_DATE))
                         .fetch();

        List<TimelineData.TimelineDay> days = dayRows.stream()
                                                     .map(r -> new TimelineData.TimelineDay(
                                                             r.get("yr", Integer.class),
                                                             r.get("mo", Integer.class),
                                                             r.get("dy", Integer.class),
                                                             r.get("cnt", Integer.class)))
                                                     .toList();

        int undatedCount = dsl.fetchCount(PHOTO, PHOTO.CAPTURE_DATE.isNull());

        return new TimelineData(days, undatedCount);
    }

    /**
     * Photos for a TIMELINE_MONTH or TIMELINE_DAY node.
     * Pass {@code day = null} to get the whole month.
     */
    public List<PhotoRecord> findByCaptureDate(int year, int month, Integer day) {
        var condition = PHOTO.CAPTURE_DATE.isNotNull()
                                          .and(DSL.year(PHOTO.CAPTURE_DATE)
                                                  .eq(year))
                                          .and(DSL.month(PHOTO.CAPTURE_DATE)
                                                  .eq(month));

        if (day != null) {
            condition = condition.and(DSL.day(PHOTO.CAPTURE_DATE)
                                         .eq(day));
        }

        return dsl.selectFrom(PHOTO)
                  .where(condition)
                  .orderBy(PHOTO.CAPTURE_DATE, PHOTO.FILENAME)
                  .fetch();
    }

    /**
     * Photos whose {@code capture_date} is NULL — feeds the Undated node.
     */
    public List<PhotoRecord> findByNullCaptureDate() {
        return dsl.selectFrom(PHOTO)
                  .where(PHOTO.CAPTURE_DATE.isNull())
                  .orderBy(PHOTO.FILENAME)
                  .fetch();
    }

    public Optional<PhotoRecord> findById(long photoId) {
        return Optional.ofNullable(
                dsl.selectFrom(PHOTO)
                   .where(PHOTO.ID.eq(photoId))
                   .fetchOne());
    }

    public List<PhotoRecord> findByIdIn(Collection<Long> ids) {
        return dsl.selectFrom(PHOTO)
                  .where(PHOTO.ID.in(ids))
                  .fetch()
                  .stream()
                  .toList();
    }

    public Query markFaceDetectDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(PHOTO)
                  .set(PHOTO.AI_FACE_DETECT_DONE, true)
                  .set(PHOTO.AI_UPDATED_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    public Query markFaceEmbedDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(PHOTO)
                  .set(PHOTO.AI_FACE_EMBED_DONE, true)
                  .set(PHOTO.AI_UPDATED_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    public Query markClipEmbedDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(PHOTO)
                  .set(PHOTO.AI_CLIP_EMBED_DONE, true)
                  .set(PHOTO.AI_UPDATED_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    public Query markLuceneIndexDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(PHOTO)
                  .set(PHOTO.AI_LUCENE_INDEX_DONE, true)
                  .set(PHOTO.AI_UPDATED_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    public Query markErrorQuery(long photoId, String errorMessage, LocalDateTime now) {
        String truncated = errorMessage != null && errorMessage.length() > 1024
                ? errorMessage.substring(0, 1021) + "..."
                : errorMessage;
        return dsl.update(PHOTO)
                  .set(PHOTO.AI_LAST_ERROR, truncated)
                  .set(PHOTO.AI_RETRY_COUNT,
                          PHOTO.AI_RETRY_COUNT.add((short) 1))
                  .set(PHOTO.AI_UPDATED_AT, now)
                  .where(PHOTO.ID.eq(photoId));
    }

    /**
     * Returns photo IDs that still need face detection (status row exists but flag is false).
     */
    public List<Long> findPendingFaceDetect() {
        return dsl.select(PHOTO.ID)
                  .from(PHOTO)
                  .where(PHOTO.AI_FACE_DETECT_DONE.eq(false))
                  .fetch(PHOTO.ID);
    }

    /** FIXME: Unneeded?
     * Returns photo IDs that still need face embedding.
     */
    public List<Long> findPendingFaceEmbed() {
        return dsl.select(PHOTO.ID)
                  .from(PHOTO)
                  .where(PHOTO.AI_FACE_DETECT_DONE.eq(true))
                  .and(PHOTO.AI_FACE_EMBED_DONE.eq(false))
                  .fetch(PHOTO.ID);
    }

    /**
     * Returns photo IDs that still need CLIP embedding.
     */
    public List<Long> findPendingClipEmbed() {
        return dsl.select(PHOTO.ID)
                  .from(PHOTO)
                  .where(PHOTO.AI_CLIP_EMBED_DONE.eq(false))
                  .fetch(PHOTO.ID);
    }

    /**
     * Returns photo IDs that still need Lucene indexing.
     */
    public List<Long> findPendingLuceneIndex() {
        return dsl.select(PHOTO.ID)
                  .from(PHOTO)
                  .where(PHOTO.AI_CLIP_EMBED_DONE.eq(true))
                  .and(PHOTO.AI_LUCENE_INDEX_DONE.eq(false))
                  .fetch(PHOTO.ID);
    }

    public Query resetAiFields() {
        return dsl
                .update(PHOTO)
                .set(PHOTO.AI_UPDATED_AT, timeProvider.now())
                .set(PHOTO.AI_CLIP_EMBED_DONE, false)
                .set(PHOTO.AI_FACE_DETECT_DONE, false)
                .set(PHOTO.AI_FACE_EMBED_DONE, false)
                .set(PHOTO.AI_LUCENE_INDEX_DONE, false);
    }

    public List<PhotoRecord> findOrderedByCaptureDate() {
        return dsl.selectFrom(PHOTO)
                  .where(PHOTO.CAPTURE_DATE.isNotNull())
                  .orderBy(PHOTO.CAPTURE_DATE)
                  .fetch();
    }

    public List<PhotoRecord> findAllWithGps() {
        return dsl.selectFrom(PHOTO)
                  .where(PHOTO.GPS_LAT.isNotNull()
                                      .and(PHOTO.GPS_LON.isNotNull()))
                  .fetch();
    }
}
