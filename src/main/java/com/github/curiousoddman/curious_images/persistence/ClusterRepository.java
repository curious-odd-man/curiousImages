package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CLUSTER;

/**
 * Hand-written jOOQ repository for {@code cluster}.
 * <p>
 * A cluster is one clustering group — what {@code face-person-correction-requirements.md} calls a
 * "prototype". {@code cluster.person_id} is the single source of truth for who a face belongs to:
 * a face links to a person indirectly via {@code face.cluster_id -> cluster.person_id}, never
 * directly. This is what makes {@link #reassignAllOwnedByQuery} (FR4 merge) a one-statement
 * operation — merging two persons never needs to touch individual face rows.
 * <p>
 * All writes return unexecuted {@link Query} objects for caller-controlled batching, except
 * {@link #insert}, which needs its generated ID back synchronously (same reasoning as
 * {@code PersonRepository#insert}).
 */
@Repository
@RequiredArgsConstructor
public class ClusterRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new cluster row and returns the generated ID. Executes immediately — the caller
     * (clustering service or {@code PersonCorrectionService}) needs the ID right away to assign
     * it to face rows.
     */
    public long insert(long personId, byte[] centroidEmbedding, int memberCount, LocalDateTime now) {
        return dsl.insertInto(CLUSTER)
                  .set(CLUSTER.PERSON_ID, personId)
                  .set(CLUSTER.CENTROID_EMBEDDING, centroidEmbedding)
                  .set(CLUSTER.MEMBER_COUNT, memberCount)
                  .set(CLUSTER.CREATED_AT, now)
                  .returning(CLUSTER.ID)
                  .fetchOne()
                  .getId();
    }

    public Optional<ClusterRecord> findById(long id) {
        return Optional.ofNullable(
                dsl.selectFrom(CLUSTER)
                   .where(CLUSTER.ID.eq(id))
                   .fetchOne());
    }

    public List<ClusterRecord> findByPersonId(long personId) {
        return dsl.selectFrom(CLUSTER)
                  .where(CLUSTER.PERSON_ID.eq(personId))
                  .fetch();
    }

    /**
     * Loads every cluster in the database. Used as the in-memory candidate set for:
     * <ul>
     *   <li>the incremental fast-path (FR7), matching each new face against every existing
     *       prototype;</li>
     *   <li>seeding Pass 1 of a full rebuild with locked faces' current prototype centroids
     *       (FR7/FR8) as fixed-starting-point (but still recomputable — see the requirements
     *       doc's clarification that centroids move as membership changes) attractors.</li>
     * </ul>
     * Callers should not treat a cluster with {@code member_count == 0} as a real candidate —
     * such rows are transient and should be removed via {@link #deleteQuery} by whoever drops
     * the last member (see {@code PersonCorrectionService}), but this method makes no such
     * filtering itself in case a caller wants to audit/clean them up.
     */
    public List<ClusterRecord> findAll() {
        return dsl.selectFrom(CLUSTER)
                  .fetch();
    }

    /**
     * Updates a cluster's centroid and member count after its membership changed — the FR8
     * "recompute" step. Callers must never call this with {@code memberCount == 0}; delete the
     * cluster instead (see {@link #deleteQuery}), since a centroid with no members is meaningless
     * and would otherwise sit around as a stale, ownerless-in-practice match candidate.
     */
    public Query updateCentroidQuery(long clusterId, byte[] centroidEmbedding, int memberCount, LocalDateTime now) {
        if (memberCount <= 0) {
            throw new IllegalArgumentException(
                    "Refusing to persist a cluster with memberCount=" + memberCount +
                            " — delete it instead (see ClusterRepository#deleteQuery)");
        }
        return dsl.update(CLUSTER)
                  .set(CLUSTER.CENTROID_EMBEDDING, centroidEmbedding)
                  .set(CLUSTER.MEMBER_COUNT, memberCount)
                  .set(CLUSTER.UPDATED_AT, now)
                  .where(CLUSTER.ID.eq(clusterId));
    }

    /**
     * FR4 merge: reassigns every cluster owned by {@code sourcePersonId} to
     * {@code targetPersonId} in one statement. Centroids themselves are untouched — per FR6,
     * merging concatenates prototype lists rather than blending centroids together.
     */
    public Query reassignAllOwnedByQuery(long sourcePersonId, long targetPersonId) {
        return dsl.update(CLUSTER)
                  .set(CLUSTER.PERSON_ID, targetPersonId)
                  .where(CLUSTER.PERSON_ID.eq(sourcePersonId));
    }

    /**
     * Deletes a cluster row outright. Call this — never {@link #updateCentroidQuery} with a zero
     * count — whenever a correction (exclude / reassign / split) removes a cluster's last member,
     * so it stops being loaded by {@link #findAll} as a stale match candidate for future faces.
     */
    public Query deleteQuery(long clusterId) {
        return dsl.deleteFrom(CLUSTER)
                  .where(CLUSTER.ID.eq(clusterId));
    }
}
