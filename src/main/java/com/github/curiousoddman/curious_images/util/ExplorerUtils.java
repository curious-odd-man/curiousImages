package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows-only "Reveal in Explorer" for the photo grid's context menu. Per product decision, no
 * cross-platform fallback (Finder/{@code xdg-open}) is implemented — this simply shells out to
 * {@code explorer.exe /select,<path>}.
 */
@Slf4j
@UtilityClass
public class ExplorerUtils {

    /**
     * Opens Windows Explorer with {@code absolutePath} pre-selected/highlighted in its containing
     * folder. A missing/null path is logged and silently ignored.
     */
    public static void revealInExplorer(String absolutePath) {
        if (absolutePath == null) {
            log.warn("revealInExplorer: no path available");
            return;
        }
        Path path = Path.of(absolutePath);
        if (!Files.exists(path)) {
            log.warn("revealInExplorer: file no longer exists: {}", absolutePath);
            return;
        }
        try {
            String command = "/select,\"" + path.toAbsolutePath() + "\"";
            log.info("Submitting explorer with {}", command);
            new ProcessBuilder("explorer.exe", command)
                    .start();
        } catch (IOException e) {
            // explorer.exe can return a non-zero exit code even on success; we don't wait for or
            // check it. An IOException here means the process itself couldn't be started at all.
            log.warn("revealInExplorer: failed to launch explorer.exe for {}", absolutePath, e);
        }
    }
}
