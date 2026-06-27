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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PERSON;

/**
 * Hand-written jOOQ repository for {@code person}.
 * <p>
 * {@link #insert} executes immediately and returns the new ID — same reasoning as
 * {@code PhotoRepository.insert}: the ID is needed immediately to assign it to face rows.
 * All other writes return unexecuted {@link Query} objects for caller-controlled batching.
 */
@Repository
@RequiredArgsConstructor
public class PersonRepository {

    private static final String UNKNOWN_PERSON_NAME = "__unknown__";

    private final DSLContext dsl;

    /**
     * Inserts a new person row and returns the generated ID. Executes immediately (not batched)
     * because the clustering loop needs the ID back before it can assign faces.
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
     * Returns the ID of the shared "unknown" person row, creating it if it does not
     * exist yet.  All singleton faces (those that did not cluster with anyone) are
     * assigned here so they remain queryable.
     */
    public long findOrCreateUnknown(LocalDateTime now) {
        return dsl.select(PERSON.ID)
                  .from(PERSON)
                  .where(PERSON.NAME.eq(UNKNOWN_PERSON_NAME))
                  .fetchOptional(PERSON.ID)
                  .orElseGet(() -> insert(UNKNOWN_PERSON_NAME, null, now));
    }

    /**
     * Given the face IDs that make up a newly formed cluster, returns the ID of the
     * Person row that previously owned the largest share of those faces — i.e. the
     * best candidate for continuity of a named person across recluster runs.
     *
     * <p>Returns {@link Optional#empty()} when none of the faces were assigned to any
     * person before the wipe (e.g. on the very first run).
     *
     * <p>Note: this query runs against the FACE table <em>after</em> PERSON_ID has been
     * cleared by {@code clearAllPersonAssignments()}, so we cannot rely on the current
     * PERSON_ID value.  Instead, callers should pass the face IDs that were in each
     * cluster during the <em>previous</em> run.
     *
     * <p><b>Implementation note</b>: because the wipe sets person_id = NULL before this
     * is called, you will need to keep a snapshot of the old assignments in memory
     * (a {@code Map<Long, Long> faceIdToOldPersonId}) captured <em>before</em> the wipe
     * in {@code PersonClusteringService.cluster()}, then pass that map here or resolve it
     * in the service layer.  The method signature below accepts faceIds and an old-mapping
     * for that reason.
     * <p>
     * TODO: if you add a PREVIOUS_PERSON_ID audit column to FACE (or a separate
     *       face_person_history table) this query can be done purely in SQL.
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
