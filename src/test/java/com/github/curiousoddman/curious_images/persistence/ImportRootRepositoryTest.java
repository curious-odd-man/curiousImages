package com.github.curiousoddman.curious_images.persistence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ImportRootRepositoryTest extends AbstractRepositoryH2Test {

    private ImportRootRepository repository() {
        return new ImportRootRepository(dsl);
    }

    @Test
    void findOrCreateInsertsOnFirstCallAndReturnsSameIdOnRescan() {
        ImportRootRepository repository = repository();
        LocalDateTime        t1         = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime        t2         = LocalDateTime.of(2024, 1, 2, 10, 0);

        long firstId  = repository.findOrCreate("D:\\Photos", t1);
        long secondId = repository.findOrCreate("D:\\Photos", t2);

        assertEquals(firstId, secondId, "re-scanning the same root must not create a duplicate row");
    }

    @Test
    void differentRootsGetDifferentIds() {
        ImportRootRepository repository = repository();

        long first  = repository.findOrCreate("D:\\Photos", LocalDateTime.now());
        long second = repository.findOrCreate("D:\\OtherPhotos", LocalDateTime.now());

        assertNotEquals(first, second);
    }

    @Test
    void updateLastScannedAtPersists() {
        ImportRootRepository repository = repository();
        long                 id         = repository.findOrCreate("D:\\Photos", LocalDateTime.of(2024, 1, 1, 10, 0));

        LocalDateTime scannedAt = LocalDateTime.of(2024, 6, 1, 12, 30);
        repository.updateLastScannedAt(id, scannedAt);

        var record = dsl.selectFrom(com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_ROOT)
                        .where(com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_ROOT.ID.eq(id))
                        .fetchOne();
        assertNotNull(record);
        assertEquals(scannedAt, record.getLastScannedAt());
    }
}
