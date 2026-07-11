package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.ai.DetectedFace;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

@Repository
@RequiredArgsConstructor
public class FaceThumbnailsRepository {
    public static final int FACE_THUMBNAIL_SIZE = 128;

    private final ThumbnailGenerator thumbnailGenerator;

    @SneakyThrows
    public Path createFaceThumbnail(String originImageFullPath, BufferedImage img, DetectedFace face) {
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

        Path thumbnailPath = constructPath(originImageFullPath, x, y, w, h);
        return thumbnailGenerator.writeThumbnail(
                                         faceImage,
                                         thumbnailPath,
                                         0,
                                         FACE_THUMBNAIL_SIZE)
                                 .cachePath();
    }

    private Path constructPath(String originImageFullPath, int x, int y, int w, int h) {
        Path path = Path.of(originImageFullPath);
        String fileName = path.getFileName()
                              .toString();
        int lastIndexOfDot = fileName.lastIndexOf('.');

        String dirName = null;
        if (lastIndexOfDot > 0) {
            dirName = fileName.substring(0, lastIndexOfDot);
        } else {
            dirName = fileName;
        }

        return path.getParent()
                   .resolve(dirName)
                   .resolve("%d_%d_%d_%d.jpg".formatted(x, y, w, h));
    }
}
