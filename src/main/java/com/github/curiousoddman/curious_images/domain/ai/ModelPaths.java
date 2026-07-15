package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves runtime paths for ONNX model files under {@link AiConfig#getModelDir()}.
 * <p>
 * Models are no longer bundled in the build artifact or extracted from the classpath. Instead,
 * {@code LibraryController} checks {@link #allModelsPresent()} at startup and before submitting
 * the AI pipeline job, and — if the user agrees — submits a {@code ModelDownloadJob} via
 * {@code JobManager#submitModelDownloadJob} to fetch whatever's missing in the background.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPaths {

    private final AiConfig config;

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

    public Path resolve(String filename) {
        return config.getModelDir()
                     .resolve(filename);
    }

    public boolean allModelsPresent() {
        return missingModels().isEmpty();
    }

    /**
     * Models declared in {@code app.ai.models} (see {@link AiConfig}) whose file does not yet
     * exist under {@link AiConfig#getModelDir()}. Recomputed on every call — cheap (four
     * {@code Files.exists} checks) and avoids any stale-cache issues after a download completes.
     */
    public List<AiConfig.ModelDownload> missingModels() {
        return config.getModels()
                     .stream()
                     .filter(m -> !Files.exists(resolve(m.getFilename())))
                     .toList();
    }
}
