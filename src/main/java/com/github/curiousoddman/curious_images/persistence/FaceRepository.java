package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.FACE;

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
     * Returns an unexecuted INSERT for a single detected face row. Queue into a buffer and flush
     * via {@code dsl.transaction(cfg -> DSL.using(cfg).batch(buffer).execute())}.
     */
    public Query insertQuery(long photoId, double x, double y, double w, double h,
                             double confidence, String landmarkJson, LocalDateTime now) {
        return dsl.insertInto(FACE)
                .set(FACE.PHOTO_ID,      photoId)
                .set(FACE.BBOX_X,        x)
                .set(FACE.BBOX_Y,        y)
                .set(FACE.BBOX_W,        w)
                .set(FACE.BBOX_H,        h)
                .set(FACE.CONFIDENCE,    confidence)
                .set(FACE.LANDMARK_JSON, landmarkJson)
                .set(FACE.CREATED_AT,    now);
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
}
