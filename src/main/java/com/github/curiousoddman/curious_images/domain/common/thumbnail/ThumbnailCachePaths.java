package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves the on-disk location of a photo's cached thumbnail.
 * <p>
 * Thumbnails are sharded by {@code photo_id % 1000} to avoid one huge flat directory at the
 * 25,000-photo target scale. Only the relative part ({@code {shard}/{id}.jpg}) is persisted in
 * {@code THUMBNAIL.cache_path}, so the cache root can be relocated later via config alone.
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
