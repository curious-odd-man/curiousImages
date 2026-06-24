package com.github.curiousoddman.curious_images.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

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

    /**
     * Which ONNX execution provider to use. GPU implies CUDA first, then DirectML fallback.
     */
    private ExecutionProvider executionProvider = ExecutionProvider.CUDA;

    /**
     * Number of threads used inside a single ONNX op (intra-op parallelism).
     */
    private int intraOpThreads = 4;

    /**
     * Directory where ONNX model files are stored at runtime.
     * Models are extracted from the fat JAR to this directory on first launch.
     */
    private Path modelDir = Path.of(System.getProperty("user.home"), ".cimages", "models");

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
}
