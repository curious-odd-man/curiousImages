package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.ai.DetectedFace;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
@RequiredArgsConstructor
public class FaceThumbnailsRepository {
    public static final int FACE_THUMBNAIL_SIZE  = 128;
    public static final int MAX_DIRECTORIES      = 100;

    private final ThumbnailGenerator thumbnailGenerator;
    private final AtomicInteger      faceCounter = new AtomicInteger(0);

    @SneakyThrows
    public Path createFaceThumbnail(BufferedImage img, DetectedFace face) {
        int x = (int) (img.getWidth() * face.x());
        int y = (int) (img.getHeight() * face.y());
        int w = (int) (img.getWidth() * face.w());
        int h = (int) (img.getHeight() * face.h());

        BufferedImage faceImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D    graphics  = faceImage.createGraphics();
        graphics.drawImage(img,
                0, 0, faceImage.getWidth(), faceImage.getHeight(),
                x, y, x + w, y + h, null
        );

        Path thumbnailPath = constructPath();
        return thumbnailGenerator.writeThumbnail(
                                         faceImage,
                                         thumbnailPath,
                                         0,
                                         FACE_THUMBNAIL_SIZE)
                                 .cachePath();
    }

    private Path constructPath() {
        int i = faceCounter.addAndGet(1);

        int firstLevelDir  = i % MAX_DIRECTORIES;
        int secondLevelDir = (i / MAX_DIRECTORIES) % MAX_DIRECTORIES;

        return Path.of(
                ".cimages-faces",
                String.valueOf(firstLevelDir),
                String.valueOf(secondLevelDir),
                i + ".jpg"
        );
    }
}
