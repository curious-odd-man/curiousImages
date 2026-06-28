package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@UtilityClass
public class FileUtils {
    /**
     * Copies {@code src} to {@code dest}, skipping if the destination already
     * exists with the same file size (idempotent re-runs).
     */
    public static void copyFileIfDifferentSize(Path src, Path dest) throws IOException {
        if (Files.exists(dest) && Files.size(dest) == Files.size(src)) {
            return; // already there with same size — skip
        }
        Files.createDirectories(dest.getParent());
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }
}
