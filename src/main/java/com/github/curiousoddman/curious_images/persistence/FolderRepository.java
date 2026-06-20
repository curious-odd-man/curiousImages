package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import static com.github.curiousoddman.curious_images.dbobj.Tables.FOLDER;

/**
 * Hand-written jOOQ repository for {@code folder}. {@code (import_root_id, relative_path)} is
 * {@code UNIQUE}, so re-running a scan never duplicates folder rows.
 * <p>
 * The import root itself is represented as a folder row too ({@code relative_path = ""},
 * {@code parent_folder_id = NULL}), so every {@code PHOTO} can reference a {@code folder_id}
 * without a special case for "files directly in the root" — see implementation plan §4.
 */
@Repository
@RequiredArgsConstructor
public class FolderRepository {
    private final DSLContext dsl;

    public long findOrCreate(long importRootId, Long parentFolderId, String relativePath, String name) {
        FolderRecord existing = dsl.selectFrom(FOLDER)
                .where(FOLDER.IMPORT_ROOT_ID.eq(importRootId))
                .and(FOLDER.RELATIVE_PATH.eq(relativePath))
                .fetchOne();
        if (existing != null) {
            return existing.getId();
        }

        return dsl.insertInto(FOLDER)
                .set(FOLDER.IMPORT_ROOT_ID, importRootId)
                .set(FOLDER.PARENT_FOLDER_ID, parentFolderId)
                .set(FOLDER.RELATIVE_PATH, relativePath)
                .set(FOLDER.NAME, name)
                .returning(FOLDER.ID)
                .fetchOne()
                .getId();
    }
}
