package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.config.AiConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Resolves runtime paths for ONNX model files. On first launch each model is copied from
 * {@code src/main/resources/models/} (bundled in the fat JAR) to the user's
 * {@code ~/.cimages/models/} directory so the application remains self-contained even without
 * separate model installation steps.
 * <p>
 * Because model files can be hundreds of megabytes, the copy is skipped on subsequent launches
 * by checking whether the target file already exists.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPaths {

    private final AiConfig config;

    @PostConstruct
    public void verifyModelsExist() {
        List.of(
                    retinaFace(),
                    arcFace(),
                    clipImage(),
                    clipText()
            )
            .forEach(this::ensureExists);
    }

    private void ensureExists(Path path) {
        if (Files.exists(path)) {
            log.info("{} model exists", path);
        } else {
            extractFromClasspath("/models/" + path.getFileName()
                                                  .toString(), path);
        }
    }

    public Path retinaFace() {
        return resolve("retinaface-resnet50.onnx");
    }

    public Path arcFace() {
        return resolve("arcface_r50.onnx");
    }

    public Path clipImage() {
        return resolve("clip_image_vit_b32.onnx");
    }

    public Path clipText() {
        return resolve("clip_text_vit_b32.onnx");
    }

    private Path resolve(String filename) {
        return config.getModelDir()
                     .resolve(filename);
    }

    private void extractFromClasspath(String resource, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = ModelPaths.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "Model resource not found on classpath: " + resource +
                                    ". Download the model and place it in src/main/resources/models/. " +
                                    "See README for download instructions.");
                }
                log.info("Extracting model {} to {}", resource, target);
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Model extracted: {}", target);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract model " + resource + " to " + target, e);
        }
    }
}
