package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaVideoRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.MediaForHashing;
import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaType;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.util.Either;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.Query;
import org.jooq.Result;
import org.jooq.SelectWhereStep;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA;
import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_VIDEO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;

@Repository
@RequiredArgsConstructor
public class MediaRepository {
    private final DSLContext   dsl;
    private final TimeProvider timeProvider;

    public Optional<MediaPhotoRecord> findByAbsolutePath(String absolutePath) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.ABSOLUTE_PATH.eq(absolutePath))
                .fetchOptional();
    }

    public List<MediaPhotoRecord> findByFolderId(long folderId) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.FOLDER_ID.eq(folderId))
                .orderBy(MEDIA_PHOTO.FILENAME)
                .fetch()
                .stream()
                .toList();
    }

    public long insertPhoto(long folderId, String absolutePath, String filename, String extension,
                            long fileSize, Integer width, Integer height,
                            LocalDateTime captureDate, CaptureDateSource captureDateSource,
                            int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                            String exifExtraJson, LocalDateTime now) {
        return dsl.transactionResult(conf -> {
            DSLContext tx = DSL.using(conf);
            Long id = tx.insertInto(MEDIA)
                        .set(MEDIA.FOLDER_ID, folderId)
                        .set(MEDIA.ABSOLUTE_PATH, absolutePath)
                        .set(MEDIA.FILENAME, filename)
                        .set(MEDIA.EXTENSION, extension)
                        .set(MEDIA.FILE_SIZE, fileSize)
                        .set(MEDIA.WIDTH, width)
                        .set(MEDIA.HEIGHT, height)
                        .set(MEDIA.CAPTURE_DATE, captureDate)
                        .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                        .set(MEDIA.CAMERA_MAKE, cameraMake)
                        .set(MEDIA.CAMERA_MODEL, cameraModel)
                        .set(MEDIA.IMPORTED_AT, now)
                        .set(MEDIA.LAST_SEEN_AT, now)
                        .set(MEDIA.AI_UPDATED_AT, now)
                        .set(MEDIA.MEDIA_TYPE, MediaType.PHOTO)
                        .returning(MEDIA.ID)
                        .fetchOne()
                        .getId();
            tx.insertInto(PHOTO)
              .set(PHOTO.ID, id)
              .set(PHOTO.ORIENTATION, orientationDegrees)
              .set(PHOTO.LENS_MODEL, lensModel)
              .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
              .execute();
            return id;
        });
    }

    public Query touchLastSeenAtQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.LAST_SEEN_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public List<Query> updateMetadataQuery(long mediaId, long fileSize, Integer width, Integer height,
                                           LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                           int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                                           String exifExtraJson, LocalDateTime now) {
        return List.of(
                dsl.update(MEDIA)
                   .set(MEDIA.FILE_SIZE, fileSize)
                   .set(MEDIA.WIDTH, width)
                   .set(MEDIA.HEIGHT, height)
                   .set(MEDIA.CAPTURE_DATE, captureDate)
                   .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                   .set(PHOTO.ORIENTATION, orientationDegrees)
                   .set(MEDIA.CAMERA_MAKE, cameraMake)
                   .set(MEDIA.CAMERA_MODEL, cameraModel)
                   .set(MEDIA.LAST_SEEN_AT, now)
                   .where(MEDIA.ID.eq(mediaId)),

                dsl.update(PHOTO)
                   .set(PHOTO.LENS_MODEL, lensModel)
                   .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
                   .where(PHOTO.ID.eq(mediaId))
        );
    }

    public void updatePhotoOrientationAndResetAi(DSLContext ctx, long mediaId, int newOrientationDegrees, LocalDateTime now) {
        ctx.update(PHOTO)
           .set(PHOTO.ORIENTATION, newOrientationDegrees)
           .where(PHOTO.ID.eq(mediaId))
           .execute();

        ctx.update(MEDIA)
           .set(MEDIA.AI_FACE_DETECT_DONE, false)
           .set(MEDIA.AI_FACE_EMBED_DONE, false)
           .set(MEDIA.AI_CLIP_EMBED_DONE, false)
           .set(MEDIA.AI_LUCENE_INDEX_DONE, false)
           .set(MEDIA.AI_LAST_ERROR, (String) null)
           .set(MEDIA.AI_RETRY_COUNT, (short) 0)
           .set(MEDIA.AI_UPDATED_AT, now)
           .where(MEDIA.ID.eq(mediaId))
           .execute();
    }

    public void deleteById(DSLContext ctx, long mediaId) {
        ctx.deleteFrom(MEDIA)
           .where(MEDIA.ID.eq(mediaId))
           .execute();
    }

    public List<MediaForHashing> findAllForHashing() {
        return dsl.select(MEDIA.ID, MEDIA.ABSOLUTE_PATH, MEDIA.EXTENSION, MEDIA.FILE_SIZE)
                  .from(MEDIA)
                  .fetch(r ->
                          new MediaForHashing(
                                  r.get(MEDIA.ID),
                                  r.get(MEDIA.ABSOLUTE_PATH),
                                  r.get(MEDIA.EXTENSION),
                                  r.get(MEDIA.FILE_SIZE)
                          )
                  );
    }


    // ── Timeline queries ──────────────────────────────────────────────────────────
    public TimelineData findTimelineData() {
        var dayRows = dsl.select(
                                 DSL.year(MEDIA.CAPTURE_DATE)
                                    .as("yr"),
                                 DSL.month(MEDIA.CAPTURE_DATE)
                                    .as("mo"),
                                 DSL.day(MEDIA.CAPTURE_DATE)
                                    .as("dy"),
                                 DSL.count()
                                    .as("cnt"))
                         .from(MEDIA)
                         .where(MEDIA.CAPTURE_DATE.isNotNull())
                         .groupBy(DSL.year(MEDIA.CAPTURE_DATE),
                                 DSL.month(MEDIA.CAPTURE_DATE),
                                 DSL.day(MEDIA.CAPTURE_DATE))
                         .orderBy(DSL.year(MEDIA.CAPTURE_DATE),
                                 DSL.month(MEDIA.CAPTURE_DATE),
                                 DSL.day(MEDIA.CAPTURE_DATE))
                         .fetch();

        List<TimelineData.TimelineDay> days = dayRows.stream()
                                                     .map(r -> new TimelineData.TimelineDay(
                                                             r.get("yr", Integer.class),
                                                             r.get("mo", Integer.class),
                                                             r.get("dy", Integer.class),
                                                             r.get("cnt", Integer.class)))
                                                     .toList();

        int undatedCount = dsl.fetchCount(MEDIA, MEDIA.CAPTURE_DATE.isNull());

        return new TimelineData(days, undatedCount);
    }

    public List<Media> findByCaptureDate(int year, int month, Integer day) {
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(conditionByDate(MEDIA_VIDEO.CAPTURE_DATE, year, month, day))
                .orderBy(MEDIA_VIDEO.CAPTURE_DATE, MEDIA_VIDEO.FILENAME)
                .fetch();
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(conditionByDate(MEDIA_PHOTO.CAPTURE_DATE, year, month, day))
                .orderBy(MEDIA_PHOTO.CAPTURE_DATE, MEDIA_PHOTO.FILENAME)
                .fetch();


        Stream<Either<MediaPhotoRecord, MediaVideoRecord>> videoStream = video.stream()
                                                                              .map(Either::right);
        Stream<Either<MediaPhotoRecord, MediaVideoRecord>> photoStream = photo.stream()
                                                                              .map(Either::left);

        Function<Either<MediaPhotoRecord, MediaVideoRecord>, LocalDateTime> sortKeyOf = e -> {
            if (e.isLeft()) {
                return e.getLeft()
                        .getCaptureDate();
            } else {
                return e.getRight()
                        .getCaptureDate();
            }
        };

        return Stream.concat(videoStream, photoStream)
                     .sorted(Comparator.comparing(sortKeyOf))
                     .map(Media::new)
                     .toList();
    }

    public List<MediaPhotoRecord> findByNullCaptureDate() {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.CAPTURE_DATE.isNull())
                .orderBy(MEDIA_PHOTO.FILENAME)
                .fetchInto(MediaPhotoRecord.class);
    }

    public Optional<MediaPhotoRecord> findById(long mediaId) {
        return Optional.ofNullable(
                selectPhotoMedia()
                        .where(MEDIA_PHOTO.ID.eq(mediaId))
                        .fetchOne());
    }

    public List<MediaPhotoRecord> findByIdIn(Collection<Long> ids) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.ID.in(ids))
                .fetch()
                .stream()
                .toList();
    }

    public Query markFaceDetectAndEmbedDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_FACE_DETECT_DONE, true)
                  .set(MEDIA.AI_FACE_EMBED_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markClipEmbedDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_CLIP_EMBED_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markLuceneIndexDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_LUCENE_INDEX_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markErrorQuery(long mediaId, String errorMessage, LocalDateTime now) {
        String truncated = errorMessage != null && errorMessage.length() > 1024
                ? errorMessage.substring(0, 1021) + "..."
                : errorMessage;
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_LAST_ERROR, truncated)
                  .set(MEDIA.AI_RETRY_COUNT,
                          MEDIA.AI_RETRY_COUNT.add((short) 1))
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public List<Long> findPendingFaceDetect() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_FACE_DETECT_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public List<Long> findPendingClipEmbed() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_CLIP_EMBED_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public List<Long> findPendingLuceneIndex() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_CLIP_EMBED_DONE.eq(true))
                  .and(MEDIA.AI_LUCENE_INDEX_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public Query resetAiFields(long mediaId) {
        return dsl
                .update(MEDIA)
                .set(MEDIA.AI_UPDATED_AT, timeProvider.now())
                .set(MEDIA.AI_CLIP_EMBED_DONE, false)
                .set(MEDIA.AI_FACE_DETECT_DONE, false)
                .set(MEDIA.AI_FACE_EMBED_DONE, false)
                .set(MEDIA.AI_LUCENE_INDEX_DONE, false)
                .set(MEDIA.AI_TAG_DONE, false)
                .where(MEDIA.ID.eq(mediaId));
    }

    public List<MediaPhotoRecord> findOrderedByCaptureDate() {
        return dsl.selectFrom(MEDIA)
                  .where(MEDIA.CAPTURE_DATE.isNotNull())
                  .orderBy(MEDIA.CAPTURE_DATE)
                  .fetchInto(MediaPhotoRecord.class);
    }

    public List<MediaPhotoRecord> findAllWithGps() {
        return dsl.selectFrom(MEDIA)
                  .where(MEDIA.GPS_LAT.isNotNull()
                                      .and(MEDIA.GPS_LON.isNotNull()))
                  .fetchInto(MediaPhotoRecord.class);
    }

    public List<Long> findPendingAiTagging() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_TAG_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public Query markTaggingDone(Long mediaId) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_TAG_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, timeProvider.now())
                  .where(MEDIA.ID.eq(mediaId));
    }

    private SelectWhereStep<MediaPhotoRecord> selectPhotoMedia() {
        return dsl.selectFrom(MEDIA_PHOTO);
    }


    private SelectWhereStep<MediaVideoRecord> selectVideoMedia() {
        return dsl.selectFrom(MEDIA_VIDEO);
    }

    private static String sourceName(CaptureDateSource captureDateSource) {
        return captureDateSource == null ? null : captureDateSource.name();
    }

    private JSON toJson(String json) {
        return json == null ? null : JSON.valueOf(json);
    }

    public Media findMediaById(Long mediaId) {
        return selectPhotoMedia().where(MEDIA_PHOTO.ID.eq(mediaId))
                                 .fetchOptional()
                                 .map(Media::photo)
                                 .orElseGet(() -> selectVideoMedia().where(MEDIA_VIDEO.ID.eq(mediaId))
                                                                    .fetchOptional()
                                                                    .map(Media::video)
                                                                    .orElse(null));
    }

    private static Condition conditionByDate(TableField<?, LocalDateTime> field, int year, int month, Integer day) {
        Condition condition = field.isNotNull()
                                   .and(DSL.year(field)
                                           .eq(year))
                                   .and(DSL.month(field)
                                           .eq(month));

        if (day != null) {
            condition = condition.and(DSL.day(field)
                                         .eq(day));
        }
        return condition;
    }
}
