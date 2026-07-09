package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CLUSTER;
import static com.github.curiousoddman.curious_images.dbobj.Tables.FACE;
import static java.util.stream.Collectors.toMap;

/**
 * Hand-written jOOQ repository for {@code face}.
 * <p>
 * A face no longer stores its owning person directly: {@code face.cluster_id} points at a
 * {@code cluster} row (one clustering group / "prototype"), and {@code cluster.person_id} is the
 * actual owner. See {@link ClusterRepository} for why this makes FR4 (merge) trivial.
 * {@code cluster_id = NULL} means "not currently clustered" — this is also how the old shared
 * "Unknown" person is represented now: there is no such person row any more, an unclustered face
 * simply has no cluster.
 * <p>
 * All writes return unexecuted {@link Query} objects for caller-controlled batching except
 * {@link #insertAndGetId}, which is called from the merged detect+embed pipeline and needs the
 * face id back synchronously.
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

    public Optional<FaceRecord> findById(long id) {
        return Optional.ofNullable(
                dsl.selectFrom(FACE)
                   .where(FACE.ID.eq(id))
                   .fetchOne());
    }

    /**
     * Batch lookup by id — used by {@code PersonCorrectionService} when the UI hands over a
     * multi-selection of faces (FR3) to move/confirm/exclude together.
     */
    public List<FaceRecord> findByIds(Collection<Long> ids) {
        return dsl.selectFrom(FACE)
                  .where(FACE.ID.in(ids))
                  .fetch();
    }

    public List<FaceRecord> findByPhotoId(long photoId) {
        return dsl.selectFrom(FACE)
                  .where(FACE.PHOTO_ID.eq(photoId))
                  .fetch();
    }

    /**
     * All faces belonging to a person, across every cluster they own — i.e. the union of
     * {@link #findByClusterId} over {@code ClusterRepository#findByPersonId(personId)}, done in
     * one query. Convenience method for UI call sites ({@code PersonDetailController}) that don't
     * care about cluster boundaries and just want "this person's faces".
     */
    public List<FaceRecord> findByPersonId(long personId) {
        return dsl.selectFrom(FACE)
                  .where(FACE.CLUSTER_ID.in(
                          dsl.select(CLUSTER.ID)
                             .from(CLUSTER)
                             .where(CLUSTER.PERSON_ID.eq(personId))))
                  .fetch();
    }

    /**
     * All faces currently belonging to a given cluster. Note this is narrower than
     * {@link #findByPersonId} — a person can own several clusters (FR6).
     */
    public List<FaceRecord> findByClusterId(long clusterId) {
        return dsl.selectFrom(FACE)
                  .where(FACE.CLUSTER_ID.eq(clusterId))
                  .fetch();
    }

    /**
     * Faces that are not currently in any cluster and not excluded — i.e. the "Unknown" view.
     * This includes both genuinely new faces awaiting their first clustering pass AND faces that
     * were tested before and matched nothing yet; both get retested by the incremental fast-path
     * every run, which is exactly what makes early misses self-correcting as more prototypes
     * accumulate (FR8).
     */
    public List<FaceRecord> findUnclustered() {
        return dsl.selectFrom(FACE)
                  .where(FACE.CLUSTER_ID.isNull())
                  .and(FACE.EXCLUDED.isFalse())
                  .fetch();
    }

    /**
     * Faces available to a full rebuild's Pass 1/2 — i.e. everything except locked and excluded
     * faces (FR7 point 3: locked faces are never candidates for reassignment).
     */
    public List<FaceRecord> findUnlockedNonExcluded() {
        return dsl.selectFrom(FACE)
                  .where(FACE.ASSIGNMENT_LOCKED.isFalse())
                  .and(FACE.EXCLUDED.isFalse())
                  .fetch();
    }

    /**
     * Locked, non-excluded faces — used by a full rebuild to find each existing prototype that
     * must seed Pass 1 as a fixed-starting-point attractor (FR7 point 3).
     */
    public List<FaceRecord> findLockedNonExcluded() {
        return dsl.selectFrom(FACE)
                  .where(FACE.ASSIGNMENT_LOCKED.isTrue())
                  .and(FACE.EXCLUDED.isFalse())
                  .fetch();
    }

    /**
     * Assigns a face to a cluster. Used by both automatic clustering (which never touches
     * {@code assignment_locked}) and, indirectly, by manual corrections (which additionally set
     * {@code assignment_locked = true} via {@link #lockFaceAssignmentQuery}).
     */
    public Query assignClusterQuery(long faceId, long clusterId) {
        return dsl.update(FACE)
                  .set(FACE.CLUSTER_ID, clusterId)
                  .where(FACE.ID.eq(faceId));
    }

    /**
     * FR1/FR2/FR3: assigns {@code faceId} to {@code clusterId} and marks the assignment as
     * human-confirmed, protecting it from being moved by future automatic clustering (NFR1).
     * Locking does not exclude the face from centroid computation — see the requirements doc.
     */
    public Query lockFaceAssignmentQuery(long faceId, long clusterId) {
        return dsl.update(FACE)
                  .set(FACE.CLUSTER_ID, clusterId)
                  .set(FACE.ASSIGNMENT_LOCKED, true)
                  .where(FACE.ID.eq(faceId));
    }

    /**
     * FR5: flags a face as "not a person". Excluded faces are pulled out of their current
     * cluster (the caller is responsible for recomputing/deleting that cluster — see
     * {@code PersonCorrectionService}) and must never be reconsidered as a clustering candidate.
     * Also clears {@code assignment_locked}: "locked" asserts a confirmed identity, which makes
     * no sense once the face has been declared not a person at all.
     */
    public Query excludeFaceQuery(long faceId) {
        return dsl.update(FACE)
                  .set(FACE.EXCLUDED, true)
                  .set(FACE.ASSIGNMENT_LOCKED, false)
                  .set(FACE.CLUSTER_ID, (Long) null)
                  .where(FACE.ID.eq(faceId));
    }

    /**
     * Un-excludes a face. It re-enters the pool as unlocked and unclustered — deliberately not
     * restored to its old cluster (that assignment wasn't necessarily still valid, and we don't
     * keep history of it) — so the next clustering pass re-evaluates it from scratch.
     */
    public Query includeFaceQuery(long faceId) {
        return dsl.update(FACE)
                  .set(FACE.EXCLUDED, false)
                  .where(FACE.ID.eq(faceId));
    }

    /**
     * Clears {@code cluster_id} for every unlocked, non-excluded face — the full-rebuild
     * equivalent of the old {@code clearAllPersonAssignments}, except locked faces' assignments
     * survive untouched (FR7 point 2) and excluded faces are already clusterless. Executes
     * immediately; call before running Pass 1 of a full rebuild.
     */
    public void clearUnlockedClusterAssignments() {
        dsl.update(FACE)
           .set(FACE.CLUSTER_ID, (Long) null)
           .where(FACE.ASSIGNMENT_LOCKED.isFalse())
           .and(FACE.EXCLUDED.isFalse())
           .execute();
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
     * Snapshot of {@code faceId -> personId} for every currently-clustered face, resolved via
     * {@code face.cluster_id -> cluster.person_id}. Used by a full rebuild's continuity heuristic
     * ({@code PersonRepository#findPersonIdOwningMostFaces}) to prefer re-linking a newly-formed
     * cluster to whichever named person previously owned most of its faces, rather than always
     * minting a new person.
     */
    public Map<Long, Long> loadFacePersonSnapshot() {
        return dsl.select(FACE.ID, CLUSTER.PERSON_ID)
                  .from(FACE)
                  .join(CLUSTER)
                  .on(FACE.CLUSTER_ID.eq(CLUSTER.ID))
                  .fetch()
                  .stream()
                  .collect(toMap(r -> r.get(FACE.ID), r -> r.get(CLUSTER.PERSON_ID)));
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
