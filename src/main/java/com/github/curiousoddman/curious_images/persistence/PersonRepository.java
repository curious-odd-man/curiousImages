package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
}
