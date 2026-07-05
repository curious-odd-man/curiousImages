package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves the on-disk location of a photo's cached thumbnail.
 * <p>
 * The cached path mirrors the photo's full absolute source path under the configured cache root
 * (e.g. {@code /photos/2024/beach.jpg} → {@code <cacheRoot>/photos/2024/beach.jpg}) — not sharded
 * by {@code photo_id}. {@code THUMBNAIL.cache_path} stores the fully resolved path, so relocating
 * the cache root requires re-generating thumbnails rather than just updating config.
 */
@Component
public class ThumbnailCachePaths {
    private final Path cacheRoot;

    public ThumbnailCachePaths(@Value("${app.thumbnail-cache.dir}") String configuredCacheRoot) {
        this.cacheRoot = expandHome(configuredCacheRoot);
    }

    public Path resolve(Path originalPath) {
        Path absolutePath = originalPath.toAbsolutePath();
        Path relativeToRoot = absolutePath.getRoot()
                                          .relativize(absolutePath);
        return cacheRoot.resolve(relativeToRoot);
    }

    private static Path expandHome(String configuredPath) {
        if (configuredPath.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), configuredPath.substring(1))
                       .toAbsolutePath();
        }
        return Path.of(configuredPath)
                   .toAbsolutePath();
    }
}
