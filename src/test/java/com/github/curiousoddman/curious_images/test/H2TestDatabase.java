package com.github.curiousoddman.curious_images.test;

import com.github.curiousoddman.curious_images.config.DataSourceConfig;
import com.github.curiousoddman.curious_images.domain.ExceptionTranslator;
import lombok.experimental.UtilityClass;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Spins up a throwaway, uniquely-named in-memory H2 database, migrated with the real
 * {@code src/main/resources/db/migration} Flyway scripts, and returns a {@link DSLContext}
 * against it. Used by repository tests and {@code ImportServiceTest} alike so both exercise the
 * real schema rather than a hand-stubbed one.
 */
@UtilityClass
public class H2TestDatabase {
    public static DSLContext freshMigratedDatabase() {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        Flyway.configure()
              .dataSource(jdbcUrl, "sa", "sa")
              .locations("classpath:db/migration")
              .load()
              .migrate();

        DataSourceProperties props = new DataSourceProperties();
        props.setUsername("sa");
        props.setPassword("sa");
        props.setUrl(jdbcUrl);
        DataSourceConfig dataSourceConfig = new DataSourceConfig();
        DataSource       dataSource       = dataSourceConfig.getDataSource(props);
        return new DefaultDSLContext(dataSourceConfig.configuration(dataSource, new ExceptionTranslator()));
    }
}
