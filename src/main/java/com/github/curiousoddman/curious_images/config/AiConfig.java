package com.github.curiousoddman.curious_images.config;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.pattern.OpenCV;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * AI inference configuration bound from {@code application.yaml} under the {@code ai:} prefix.
 * <p>
 * Defaults: CPU execution provider, 4 intra-op threads, batch size 8, model directory
 * {@code ~/.cimages/models/}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiConfig {

    @PostConstruct
    public void initOpenCv() {
        OpenCV.loadLocally(); // org.openpnp packages this helper; call once at startup
    }

    /**
     * Which ONNX execution provider to use. GPU implies CUDA first, then DirectML fallback.
     */
    private ExecutionProvider executionProvider = ExecutionProvider.CPU;

    /**
     * Number of threads used inside a single ONNX op (intra-op parallelism).
     */
    private int intraOpThreads = 4;

    /**
     * Directory where ONNX model files are stored at runtime. Models are downloaded into this
     * directory on demand (see {@code ModelDownloadJob}) rather than bundled in the build
     * artifact.
     */
    private Path modelDir = Path.of(System.getProperty("user.home"), "cimages", "models");

    /**
     * The set of model files the AI pipeline needs and where to download each one from if it's
     * missing from {@link #modelDir}. Overridable via {@code app.ai.models} in
     * {@code application.yaml}; defaults below mirror what used to be baked into the
     * {@code downloadModels} Gradle task.
     */
    private List<ModelDownload> models = List.of(
            new ModelDownload(
                    "retinaface-resnet50.onnx",
                    "https://huggingface.co/TheEeeeLin/HivisionIDPhotos_matting/resolve/main/retinaface-resnet50.onnx"
            ),
            new ModelDownload(
                    "arcface_r50.onnx",
                    "https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx"
            ),
            new ModelDownload(
                    "clip_image_vit_b32.onnx",
                    "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/visual/model.onnx"
            ),
            new ModelDownload(
                    "clip_text_vit_b32.onnx",
                    "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/textual/model.onnx"
            )
    );

    /**
     * Number of images processed per ONNX inference call. Tune up for GPU, down if RAM-constrained.
     */
    private int batchSize = 8;

    /**
     * Gap in hours between photos that starts a new event album.
     */
    private int eventGapHours = 6;

    /**
     * Minimum photos in a time gap to create an event album.
     */
    private int minEventSize = 5;

    /**
     * Minimum photos sharing a GPS cell to create a location album.
     */
    private int minLocationSize = 3;

    /**
     * Minimum photos in a visual-similarity cluster to create a similarity album.
     */
    private int minClusterSize = 10;

    /**
     * Minimum intra-cluster average cosine similarity to accept a similarity album.
     */
    private float minClusterSimilarity = 0.6f;

    /**
     * Thread-pool size for {@code DuplicateDetectionJob}'s hashing phase. Not bound directly from
     * {@code app.duplicate-detection.thread-count} (kept here instead so it's mutable at runtime
     * via {@code AiSettingsService}); seeded from that property at startup.
     */
    private int duplicateDetectionThreadCount = 4;

    /**
     * When {@code true}, the AI pipeline only runs face detection/recognition and skips CLIP
     * embedding generation. Seeded from {@code ai.features.face-only} at startup, then mutable
     * at runtime via {@code AiSettingsService}.
     */
    private boolean faceOnly = false;

    public enum ExecutionProvider {
        /**
         * Run on CPU only (ONNX Runtime default).
         */
        CPU,
        /**
         * NVIDIA CUDA GPU execution. Requires CUDA 12+ drivers.
         */
        CUDA,
        /**
         * DirectX 12 GPU execution (AMD / Intel / NVIDIA, no CUDA drivers needed).
         */
        DIRECTML
    }

    /**
     * One downloadable model file: its filename under {@link #modelDir} and the URL to fetch it
     * from. Plain mutable POJO (not a record) so Spring's relaxed {@code @ConfigurationProperties}
     * binding can populate it from {@code application.yaml} if the defaults are overridden.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelDownload {
        private String filename;
        private String url;
    }
}
