package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OrtException;
import com.github.curiousoddman.curious_images.config.AiConfig;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
class RetinaFaceDetectorTest {

    @Test
    void faceDetectionTest() throws IOException, OrtException {
        AiConfig   config = new AiConfig();
        ModelPaths paths  = new ModelPaths(config);
        paths.verifyModelsExist();
        RetinaFaceDetector retinaFaceDetector = new RetinaFaceDetector(
                new OnnxModelRegistry(config),
                paths
        );
        BufferedImage      bufferedImage = ImageIO.read(new File("D:\\Programming\\sample-data\\sample-images\\Музыкалка Выпускной\\2005_05_Vitalik 030.jpg"));
        List<DetectedFace> detectedFaces = retinaFaceDetector.detect(bufferedImage);

        Files.createDirectories(Path.of("faces"));
        for (int i = 0; i < detectedFaces.size(); i++) {
            DetectedFace detectedFace = detectedFaces.get(i);
            log.info("\t{}", detectedFace);
            saveWithRect("faces/detected-face-" + i + ".jpg", bufferedImage, detectedFace);
            if (i > 10) {
                throw new IllegalStateException("too much faces");
            }
        }
    }

    @SneakyThrows
    private void saveWithRect(String fileName, BufferedImage source, DetectedFace detectedFace) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());

        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(source, null, null);
            g.setColor(Color.RED);
            int x = (int) (source.getWidth() * detectedFace.x());
            int y = (int) (source.getHeight() * detectedFace.y());
            int w = (int) (source.getWidth() * detectedFace.w());
            int h = (int) (source.getHeight() * detectedFace.h());
            g.drawRect(x, y, w, h);
            g.drawString("c=" + detectedFace.confidence(), x + 5, y + 5);
            g.setColor(Color.GREEN);
            for (float[] landmark : detectedFace.landmarks()) {
                int xx = (int) landmark[0];
                int yy = (int) landmark[1];
                g.drawOval(xx, yy, 3, 3);

            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "jpg", new File(fileName));
    }
}