package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.FACE;
import static java.util.stream.Collectors.toMap;

/**
 * Hand-written jOOQ repository for {@code face}.
 * All writes return unexecuted {@link Query} objects for caller-controlled batching except
 * {@link #assignPerson}, which is called from the clustering service inside its own transaction.
 */
@Repository
@RequiredArgsConstructor
public class FaceRepository {

    private final DSLContext dsl;

    /**
     * Inserts a single detected face row immediately and returns its generated id.
     * Unlike {@link #insertQuery}, this executes right away rather than being queued for
     * batching — needed by the merged detect+embed pipeline, which requires the face id
     * synchronously to write the corresponding face_embedding row in the same pass.
     */
    public long insertAndGetId(long photoId, double x, double y, double w, double h,
                               double confidence, Landmarks landmarks, LocalDateTime now, Path thumbnailPath) {
        return createRecord(photoId, x, y, w, h, confidence, landmarks, now, thumbnailPath)
                .returning(FACE.ID)
                .fetchOne()
                .getId();
    }

    /**
     * Returns an unexecuted INSERT for a single detected face row. Queue into a buffer and flush
     * via {@code dsl.transaction(cfg -> DSL.using(cfg).batch(buffer).execute())}.
     */
    public Query insertQuery(long photoId, double x, double y, double w, double h,
                             double confidence, Landmarks landmarks, LocalDateTime now, Path thumbnailPath) {
        return createRecord(photoId, x, y, w, h, confidence, landmarks, now, thumbnailPath);
    }

    public List<FaceRecord> findByPhotoId(long photoId) {
        return dsl.selectFrom(FACE)
                  .where(FACE.PHOTO_ID.eq(photoId))
                  .fetch();
    }

    public List<FaceRecord> findByPersonId(long personId) {
        return dsl.selectFrom(FACE)
                  .where(FACE.PERSON_ID.eq(personId))
                  .fetch();
    }

    /**
     * Returns all faces that have an embedding but have not yet been assigned to a person.
     * Used by {@code PersonClusteringService} to scope the clustering pass.
     */
    public List<FaceRecord> findUnassigned() {
        return dsl.selectFrom(FACE)
                  .where(FACE.PERSON_ID.isNull())
                  .fetch();
    }

    /**
     * Assigns {@code personId} to {@code faceId}. Called inside the clustering transaction.
     */
    public Query assignPersonQuery(long faceId, long personId) {
        return dsl.update(FACE)
                  .set(FACE.PERSON_ID, personId)
                  .where(FACE.ID.eq(faceId));
    }

    /**
     * Deletes all face rows for a photo. Call inside a transaction before deleting the photo.
     * Mirrors the pattern in {@code ThumbnailRepository}.
     */
    public void deleteByPhotoId(DSLContext ctx, long photoId) {
        ctx.deleteFrom(FACE)
           .where(FACE.PHOTO_ID.eq(photoId))
           .execute();
    }

    /**
     * Clears the PERSON_ID column on every FACE row without touching Person data.
     * Called at the start of a full recluster pass.
     */
    public void clearAllPersonAssignments() {
        dsl.update(FACE)
           .set(FACE.PERSON_ID, (Long) null)
           .execute();
    }

    public Map<Long, Long> loadFacePersonSnapshot() {
        return dsl.selectFrom(FACE)
                  .stream()
                  .filter(f -> f.getPersonId() != null)
                  .collect(toMap(FaceRecord::getId, FaceRecord::getPersonId));
    }

    private InsertSetMoreStep<FaceRecord> createRecord(long photoId, double x, double y, double w, double h, double confidence, Landmarks landmarks, LocalDateTime now, Path thumbnailPath) {
        return dsl.insertInto(FACE)
                  .set(FACE.PHOTO_ID, photoId)
                  .set(FACE.BBOX_X, x)
                  .set(FACE.BBOX_Y, y)
                  .set(FACE.BBOX_W, w)
                  .set(FACE.BBOX_H, h)
                  .set(FACE.CONFIDENCE, confidence)
                  .set(FACE.LANDMARK_LEFT_EYE_X, landmarks.leftEyeX())
                  .set(FACE.LANDMARK_LEFT_EYE_Y, landmarks.leftEyeY())
                  .set(FACE.LANDMARK_RIGHT_EYE_X, landmarks.rightEyeX())
                  .set(FACE.LANDMARK_RIGHT_EYE_Y, landmarks.rightEyeY())
                  .set(FACE.LANDMARK_NOSE_X, landmarks.noseX())
                  .set(FACE.LANDMARK_NOSE_Y, landmarks.noseY())
                  .set(FACE.LANDMARK_LEFT_MOUTH_X, landmarks.leftMouthX())
                  .set(FACE.LANDMARK_LEFT_MOUTH_Y, landmarks.leftMouthY())
                  .set(FACE.LANDMARK_RIGHT_MOUTH_X, landmarks.rightMouthX())
                  .set(FACE.LANDMARK_RIGHT_MOUTH_Y, landmarks.rightMouthY())
                  .set(FACE.CREATED_AT, now)
                  .set(FACE.THUMBNAIL_ABSOLUTE_PATH, thumbnailPath.toAbsolutePath()
                                                                  .toString());
    }
}
