package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailGenerator {
    public static final int LONGEST_EDGE = 512;

    private final ThumbnailCachePaths cachePaths;

    public GeneratedThumbnail writeThumbnail(BufferedImage source, Path sourceFile, int longestEdge) throws IOException {
        Path target = cachePaths.resolve(sourceFile);
        Files.createDirectories(target.getParent());

        // Thumbnailator's size(w, h) with keepAspectRatio(true) constrains the *longest* edge to
        // the given box while preserving aspect ratio — exactly the "longest edge = 512px"
        // requirement from the product spec. Rotation already happened above, so this just resizes.
        BufferedImage resized = Thumbnails.of(source)
                                          .size(longestEdge, longestEdge)
                                          .keepAspectRatio(true)
                                          .asBufferedImage();

        ImageIO.write(resized, "jpg", target.toFile());

        return new GeneratedThumbnail(target, resized.getWidth(), resized.getHeight());
    }
}
