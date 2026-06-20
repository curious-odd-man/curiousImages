package com.github.curiousoddman.curious_images.test;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.UUID;

/**
 * Spins up a throwaway, uniquely-named in-memory H2 database, migrated with the real
 * {@code src/main/resources/db/migration} Flyway scripts, and returns a {@link DSLContext}
 * against it. Used by repository tests and {@code ImportServiceTest} alike so both exercise the
 * real schema rather than a hand-stubbed one.
 */
public final class H2TestDatabase {
    private H2TestDatabase() {
    }

    public static DSLContext freshMigratedDatabase() {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "sa")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        return DSL.using(jdbcUrl, "sa", "sa");
    }
}
