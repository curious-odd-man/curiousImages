package com.github.curiousoddman.curious_images.persistence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class FolderRepositoryTest extends AbstractRepositoryH2Test {

    @Test
    void findOrCreateInsertsOnFirstCallAndReturnsSameIdOnRescan() {
        long importRootId = new ImportRootRepository(dsl).findOrCreate("D:\\Photos", LocalDateTime.now());
        FolderRepository repository = new FolderRepository(dsl);

        long firstId = repository.findOrCreate(importRootId, null, "2024\\Summer", "Summer");
        long secondId = repository.findOrCreate(importRootId, null, "2024\\Summer", "Summer");

        assertEquals(firstId, secondId, "re-scanning the same folder must not create a duplicate row");
    }

    @Test
    void theRootItselfIsRepresentedAsAFolderWithEmptyRelativePath() {
        long importRootId = new ImportRootRepository(dsl).findOrCreate("D:\\Photos", LocalDateTime.now());
        FolderRepository repository = new FolderRepository(dsl);

        long rootFolderId = repository.findOrCreate(importRootId, null, "", "Photos");

        assertTrue(rootFolderId > 0);
    }

    @Test
    void sameRelativePathUnderDifferentImportRootsAreDistinctFolders() {
        ImportRootRepository importRootRepository = new ImportRootRepository(dsl);
        long rootA = importRootRepository.findOrCreate("D:\\A", LocalDateTime.now());
        long rootB = importRootRepository.findOrCreate("D:\\B", LocalDateTime.now());
        FolderRepository repository = new FolderRepository(dsl);

        long folderInA = repository.findOrCreate(rootA, null, "Sub", "Sub");
        long folderInB = repository.findOrCreate(rootB, null, "Sub", "Sub");

        assertNotEquals(folderInA, folderInB);
    }

    @Test
    void childFolderCanReferenceParentFolderId() {
        long importRootId = new ImportRootRepository(dsl).findOrCreate("D:\\Photos", LocalDateTime.now());
        FolderRepository repository = new FolderRepository(dsl);
        long parentId = repository.findOrCreate(importRootId, null, "2024", "2024");

        long childId = repository.findOrCreate(importRootId, parentId, "2024\\Summer", "Summer");

        var childRecord = dsl.selectFrom(com.github.curiousoddman.curious_images.dbobj.Tables.FOLDER)
                .where(com.github.curiousoddman.curious_images.dbobj.Tables.FOLDER.ID.eq(childId))
                .fetchOne();
        assertNotNull(childRecord);
        assertEquals(parentId, childRecord.getParentFolderId());
    }
}
