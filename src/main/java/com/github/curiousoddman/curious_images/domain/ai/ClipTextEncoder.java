package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Wraps the CLIP ViT-B/32 text encoder ONNX model. Only called at query time (not during
 * batch import), so its session is loaded lazily and may be evicted between searches to
 * reclaim ~250 MB RAM.
 * <p>
 * Text is tokenised by {@link ClipTokenizer} to a {@code long[1][77]} tensor.
 * The encoder outputs a 512-dim embedding which is L2-normalised before returning.
 */
@Component
@RequiredArgsConstructor
public class ClipTextEncoder {

    private final OnnxModelRegistry registry;
    private final ModelPaths paths;
    private final ClipTokenizer tokenizer;

    /**
     * Encodes a free-text query into a 512-dim L2-normalised CLIP embedding.
     * The text encoder session is loaded lazily on first call and may be evicted via
     * {@link OnnxModelRegistry#evict(String)} between searches.
     */
    public float[] encode(String text) throws OrtException {
        OrtSession session = registry.getOrLoad("clip_text", paths.clipText());
        long[][] tokens = tokenizer.tokenize(text);
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), tokens);
             OrtSession.Result result = session.run(Map.of("input", tensor))) {
            float[] raw = (float[]) result.get(0).getValue();
            return l2Normalize(raw);
        } finally {
            // Evict the text encoder after each query to reclaim ~250 MB RAM.
            // It will be reloaded from disk in <1 s on SSD.
            registry.evict("clip_text");
        }
    }

    static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }
}
