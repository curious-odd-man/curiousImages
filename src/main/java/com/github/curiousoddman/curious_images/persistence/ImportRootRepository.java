package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_ROOT;

/**
 * Hand-written jOOQ repository for {@code import_root}. One row per distinct folder the user has
 * ever pointed the importer at; {@code path} is {@code UNIQUE}, which is what makes
 * {@link #findOrCreate} idempotent across repeated scans of the same root.
 * <p>
 * Not thread-safe by itself — relies on {@code ImportService}'s re-entrancy guard to ensure only
 * one import thread ever calls this concurrently.
 */
@Repository
@RequiredArgsConstructor
public class ImportRootRepository {
    private final DSLContext dsl;

    public long findOrCreate(String path, LocalDateTime now) {
        ImportRootRecord existing = dsl.selectFrom(IMPORT_ROOT)
                                       .where(IMPORT_ROOT.PATH.eq(path))
                                       .fetchOne();
        if (existing != null) {
            return existing.getId();
        }

        return dsl.insertInto(IMPORT_ROOT)
                  .set(IMPORT_ROOT.PATH, path)
                  .set(IMPORT_ROOT.CREATED_AT, now)
                  .returning(IMPORT_ROOT.ID)
                  .fetchOne()
                  .getId();
    }

    public void updateLastScannedAt(long importRootId, LocalDateTime now) {
        dsl.update(IMPORT_ROOT)
           .set(IMPORT_ROOT.LAST_SCANNED_AT, now)
           .where(IMPORT_ROOT.ID.eq(importRootId))
           .execute();
    }

    public List<ImportRootRecord> findAll() {
        return dsl.selectFrom(IMPORT_ROOT)
                  .orderBy(IMPORT_ROOT.PATH)
                  .fetch();
    }
}
