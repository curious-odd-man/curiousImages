package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.domain.ai.AiPipelineJob.loadImageOriented;
import static com.github.curiousoddman.curious_images.util.FileUtils.extensionOf;

@Slf4j
class RetinaFaceDetectorTest {

    @Test
    void faceDetectionTest() throws IOException, OrtException, IrrecoverableIterationException {
        AiConfig   config = new AiConfig();
        ModelPaths paths  = new ModelPaths(config);
        paths.verifyModelsExist();
        RetinaFaceDetector retinaFaceDetector = new RetinaFaceDetector(
                new OnnxModelRegistry(config),
                paths
        );
        PhotoMetadataExtractor metadataExtractor = new PhotoMetadataExtractor(new ObjectMapper());
        List<File>             files             = collectAllImages();
        //List<File> files           = List.of(new File("D:\\Programming\\sample-data\\sample-images\\DSC_2621.JPG"));
        int fileNameCounter = 0;
        for (File file : files) {
            ExtractedMetadata  metadata      = metadataExtractor.extract(file.toPath(), extensionOf(file.getName()));
            BufferedImage      img           = loadImageOriented(file.getAbsolutePath(), metadata.orientationDegrees());
            List<DetectedFace> detectedFaces = retinaFaceDetector.detect(img);

            Files.createDirectories(Path.of("faces"));
            fileNameCounter = saveImageWithAllFaces(detectedFaces, fileNameCounter, img);
            //fileNameCounter = saveImagePerFace(detectedFaces, fileNameCounter, rotatedImage);
        }
    }

    @SneakyThrows
    private int saveImageWithAllFaces(List<DetectedFace> detectedFaces, int fileNameCounter, BufferedImage source) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());

        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(source, null, null);
            for (DetectedFace detectedFace : detectedFaces) {
                markFaceOnImage(source, detectedFace, g);
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "jpg", new File("faces/detected-face-" + fileNameCounter + ".jpg"));

        return ++fileNameCounter;
    }

    private static void markFaceOnImage(BufferedImage source, DetectedFace detectedFace, Graphics2D g) {
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3));
        int x = (int) (source.getWidth() * detectedFace.x());
        int y = (int) (source.getHeight() * detectedFace.y());
        int w = (int) (source.getWidth() * detectedFace.w());
        int h = (int) (source.getHeight() * detectedFace.h());
        g.drawRect(x, y, w, h);
        g.setFont(new Font("Consolas", Font.PLAIN, 25));
        g.drawString("c=" + detectedFace.confidence(), x + 5, y - 5);
        g.setColor(Color.GREEN);
        float[][] landmarks = detectedFace.landmarks();
        for (int i = 0; i < landmarks.length; i++) {
            float[] landmark = landmarks[i];
            int     xx       = (int) landmark[0];
            int     yy       = (int) landmark[1];
            g.drawString("lm" + i, xx, yy);
            g.drawOval(xx, yy, 2, 10);
            g.drawOval(xx, yy, 10, 2);
        }
    }

    private int saveImagePerFace(List<DetectedFace> detectedFaces, int fileNameCounter, BufferedImage rotatedImage) {
        for (int i = 0; i < detectedFaces.size(); i++) {
            DetectedFace detectedFace = detectedFaces.get(i);
            log.info("\t{}:{}", detectedFace, Arrays.stream(detectedFace.landmarks())
                                                    .map(arr -> arr[0] + ":" + arr[1])
                                                    .collect(Collectors.joining(", ")));
            saveWithRect("faces/detected-face-" + fileNameCounter + ".jpg", rotatedImage, detectedFace);
            if (i > 20) {
                throw new IllegalStateException("too much faces");
            }
            fileNameCounter++;
        }
        return fileNameCounter;
    }

    private static List<File> collectAllImages() throws IOException {
        List<File> files = new ArrayList<>();
        Files.walkFileTree(Path.of("D:\\Programming\\sample-data"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName()
                                      .toString();
                List<String> supportedExtensions = List.of("png", "jpg");
                String       fileExt             = fileName.substring(fileName.length() - 3);
                if (Files.isRegularFile(file)
                        && supportedExtensions.contains(fileExt.toLowerCase())) {
                    files.add(file.toFile());
                }
                return super.visitFile(file, attrs);
            }
        });
        return files;
    }

    @SneakyThrows
    private void saveWithRect(String fileName, BufferedImage source, DetectedFace detectedFace) {
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), source.getType());

        Graphics2D g = image.createGraphics();
        try {
            g.drawImage(source, null, null);
            markFaceOnImage(source, detectedFace, g);
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "jpg", new File(fileName));
    }
}