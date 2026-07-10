package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PERSON;

/**
 * Hand-written jOOQ repository for {@code person}.
 * <p>
 * {@link #insert} executes immediately and returns the new ID — same reasoning as
 * {@code PhotoRepository.insert}: the ID is needed immediately to own newly-created cluster rows.
 * All other writes return unexecuted {@link Query} objects for caller-controlled batching.
 * <p>
 * There is no longer a shared "Unknown" person row. An unclustered face ({@code cluster_id IS
 * NULL}, see {@code FaceRepository#findUnclustered}) is "Unknown" purely by virtue of having no
 * cluster — nothing to create, find, or clean up here for that case any more.
 */
@Repository
@RequiredArgsConstructor
public class PersonRepository {

    private final DSLContext dsl;

    /**
     * Inserts a new person row and returns the generated ID. Executes immediately (not batched)
     * because callers (clustering, or a manual "new person" correction) need the ID back before
     * they can create/own a cluster row.
     */
    public long insert(String name, Long coverFaceId, LocalDateTime now) {
        return dsl.insertInto(PERSON)
                  .set(PERSON.NAME, name)
                  .set(PERSON.COVER_FACE_ID, coverFaceId)
                  .set(PERSON.CREATED_AT, now)
                  .returning(PERSON.ID)
                  .fetchOne()
                  .getId();
    }

    public List<PersonRecord> findAll() {
        return dsl.selectFrom(PERSON)
                  .orderBy(PERSON.ID)
                  .fetch();
    }

    public Optional<PersonRecord> findById(long id) {
        return Optional.ofNullable(
                dsl.selectFrom(PERSON)
                   .where(PERSON.ID.eq(id))
                   .fetchOne());
    }

    public Query updateNameQuery(long id, String name, LocalDateTime now) {
        return dsl.update(PERSON)
                  .set(PERSON.NAME, name)
                  .set(PERSON.UPDATED_AT, now)
                  .where(PERSON.ID.eq(id));
    }

    public Query updateCoverFaceQuery(long id, long faceId, LocalDateTime now) {
        return dsl.update(PERSON)
                  .set(PERSON.COVER_FACE_ID, faceId)
                  .set(PERSON.UPDATED_AT, now)
                  .where(PERSON.ID.eq(id));
    }

    /**
     * Persists date of birth for a person. Execute immediately — called from UI thread via task.
     */
    public void updateDob(long id, LocalDate dob, LocalDateTime now) {
        dsl.update(PERSON)
           .set(PERSON.DATE_OF_BIRTH, dob)
           .set(PERSON.UPDATED_AT, now)
           .where(PERSON.ID.eq(id))
           .execute();
    }

    /**
     * Persists free-form notes for a person. Execute immediately — called from UI thread via task.
     */
    public void updateNotes(long id, String notes, LocalDateTime now) {
        dsl.update(PERSON)
           .set(PERSON.NOTES, notes)
           .set(PERSON.UPDATED_AT, now)
           .where(PERSON.ID.eq(id))
           .execute();
    }

    /**
     * FR4: marks {@code sourcePersonId} as merged into {@code targetPersonId}. This is a
     * lightweight redirect for anything still holding a stale id from before the merge (a cached
     * UI selection, a deep link) — it is deliberately NOT consulted to resolve a face's current
     * owner, since {@code cluster.person_id} is rewritten directly at merge time (see
     * {@code ClusterRepository#reassignAllOwnedByQuery}) and is always current.
     */
    public Query markMergedIntoQuery(long sourcePersonId, long targetPersonId, LocalDateTime now) {
        return dsl.update(PERSON)
                  .set(PERSON.MERGED_INTO_ID, targetPersonId)
                  .set(PERSON.UPDATED_AT, now)
                  .where(PERSON.ID.eq(sourcePersonId));
    }

    /**
     * Resolves a possibly-stale person id through any merge redirect chain to the current owner.
     * Chains should normally be at most one hop (a person merged again after being a merge target
     * would instead have new faces/clusters land directly on the further target), but this walks
     * the whole chain defensively and bails out on a cycle rather than looping forever.
     */
    public long resolveCurrentPersonId(long personId) {
        long      current = personId;
        Set<Long> seen    = new HashSet<>();
        while (seen.add(current)) {
            Optional<PersonRecord> record = findById(current);
            if (record.isEmpty() || record.get()
                                          .getMergedIntoId() == null) {
                return current;
            }
            current = record.get()
                            .getMergedIntoId();
        }
        return current; // cycle guard — shouldn't happen, but never hang
    }

    /**
     * Given the face IDs that make up a newly formed cluster, returns the ID of the
     * Person row that previously owned the largest share of those faces — i.e. the
     * best candidate for continuity of a named person across recluster runs.
     *
     * <p>Returns {@link Optional#empty()} when none of the faces were assigned to any
     * person before the wipe (e.g. on the very first run).
     *
     * <p>Callers should pass a pre-wipe snapshot such as
     * {@code FaceRepository#loadFacePersonSnapshot}, captured before a full rebuild clears
     * unlocked faces' cluster assignments — by the time this method runs, the faces named here
     * may no longer resolve to the same person via their (now cleared) current assignment.
     */
    public Optional<Long> findPersonIdOwningMostFaces(Collection<Long> faceIds, Map<Long, Long> oldFaceIdToPersonId) {
        if (faceIds.isEmpty() || oldFaceIdToPersonId.isEmpty()) {
            return Optional.empty();
        }

        // Count how many faces in this cluster belonged to each old person
        Map<Long, Long> personVotes = new HashMap<>();
        for (Long faceId : faceIds) {
            Long oldPersonId = oldFaceIdToPersonId.get(faceId);
            if (oldPersonId != null) {
                personVotes.merge(oldPersonId, 1L, Long::sum);
            }
        }

        return personVotes.entrySet()
                          .stream()
                          .max(Map.Entry.comparingByValue())
                          .map(Map.Entry::getKey);
    }

    /**
     * IDs of persons currently redirecting into {@code personId} via {@code merged_into_id} —
     * i.e. anyone who would break if {@code personId} were deleted outright. Used as a guard
     * before {@link #deleteQuery}: never delete a person who is still a live merge target, or
     * {@link #resolveCurrentPersonId} would dead-end for those sources.
     */
    public List<Long> findMergeSourceIds(long personId) {
        return dsl.select(PERSON.ID)
                  .from(PERSON)
                  .where(PERSON.MERGED_INTO_ID.eq(personId))
                  .fetch(PERSON.ID);
    }

    /**
     * Deletes a person row outright. This is the Q1 "orphaned person" cleanup path: call only
     * after a correction (reassign/split/exclude) has left a person owning zero clusters AND the
     * user has explicitly confirmed they want that empty person record removed — see
     * {@code PersonCorrectionService#deleteOrphanedPerson}, which is the only intended caller and
     * additionally checks {@link #findMergeSourceIds} before deleting.
     */
    public Query deleteQuery(long personId) {
        return dsl.deleteFrom(PERSON)
                  .where(PERSON.ID.eq(personId));
    }

    /**
     * Updates the cover face and {@code UPDATED_AT} timestamp for an existing person.
     */
    public void updateCoverFace(long personId, long coverFaceId, LocalDateTime now) {
        dsl.update(PERSON)
           .set(PERSON.COVER_FACE_ID, coverFaceId)
           .set(PERSON.UPDATED_AT, now)
           .where(PERSON.ID.eq(personId))
           .execute();
    }
}
