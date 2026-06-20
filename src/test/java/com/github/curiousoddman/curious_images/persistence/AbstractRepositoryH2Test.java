package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.test.H2TestDatabase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;

/**
 * Spins up a fresh, throwaway in-memory H2 database (Flyway-migrated from the real
 * {@code src/main/resources/db/migration} scripts) before every test method, and exposes a
 * {@link DSLContext} against it.
 * <p>
 * Deliberately <b>not</b> a {@code @SpringBootTest} — none of the persistence repositories need
 * anything from the Spring context beyond a {@code DSLContext}, and constructing them directly
 * keeps these tests fast and avoids bootstrapping the JavaFX/Spring desktop-app context just to
 * test SQL. (An {@code application-test.yaml} profile is provided alongside this class for the
 * day a full {@code @SpringBootTest} is wanted instead.)
 */
abstract class AbstractRepositoryH2Test {

    protected DSLContext dsl;

    @BeforeEach
    void setUpDatabase() {
        dsl = H2TestDatabase.freshMigratedDatabase();
    }
}
