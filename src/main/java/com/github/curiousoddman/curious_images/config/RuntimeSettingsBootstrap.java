package com.github.curiousoddman.curious_images.config;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Persists the handful of settings that are read once at Spring context startup (before any
 * database is open) and therefore can't go through {@link com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService}
 * — that service depends on the DB, which itself lives under one of these paths.
 * <p>
 * Backed by a plain {@code .properties} file at a fixed, DB-independent location
 * ({@code ~/.cimages/runtime-settings.properties}). {@link com.github.curiousoddman.curious_images.JavafxApplication}
 * loads this file and feeds it to {@code SpringApplicationBuilder#properties} before the context
 * is built, so any override here wins over the {@code application.yaml} default. The Settings
 * screen writes to it directly and tells the user a restart is required to pick it up.
 */
@Slf4j
@UtilityClass
public class RuntimeSettingsBootstrap {

    /**
     * Keys this bootstrap file is allowed to override. Kept to an explicit allow-list so the
     * Settings screen can't accidentally clobber arbitrary Spring properties.
     */
    public static final String KEY_MODEL_DIR         = "app.ai.model-dir";
    public static final String KEY_INDEX_ROOT         = "app.ai.index-root";
    public static final String KEY_THUMBNAIL_CACHE_DIR = "app.thumbnail-cache.dir";

    private static Path file() {
        return Path.of(System.getProperty("user.home"), ".cimages", "runtime-settings.properties");
    }

    /**
     * Loads whatever overrides have been saved. Returns an empty (never null) {@link Properties}
     * if the file doesn't exist yet or can't be read, so a fresh install just falls back to
     * {@code application.yaml} defaults. Return type is {@link Properties} (rather than a
     * {@code Map<String, String>}) so it can be passed straight to
     * {@code SpringApplicationBuilder#properties(Properties)}.
     */
    public static Properties loadOverrides() {
        Path       path  = file();
        Properties props = new Properties();
        if (!Files.isRegularFile(path)) {
            return props;
        }
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Could not read runtime settings file at {}, using defaults", path, e);
        }
        return props;
    }

    /**
     * Merges {@code updates} into whatever is already saved and writes the file back. Passing a
     * {@code null} value for a key removes that override (falls back to the yaml default again).
     */
    public static void save(Map<String, String> updates) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            log.error("Could not create settings directory {}", path.getParent(), e);
            return;
        }

        Properties props = new Properties();
        if (Files.isRegularFile(path)) {
            try (var in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                log.warn("Could not read existing runtime settings file at {}, overwriting", path, e);
            }
        }

        updates.forEach((k, v) -> {
            if (v == null) {
                props.remove(k);
            } else {
                props.setProperty(k, v);
            }
        });

        try (var out = Files.newOutputStream(path)) {
            props.store(out, "curiousImages runtime settings - restart-required overrides. Edit via Settings screen.");
        } catch (IOException e) {
            log.error("Could not write runtime settings file at {}", path, e);
        }
    }
}
