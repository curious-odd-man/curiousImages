package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.util.EmbeddingMath.l2Normalize;

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
    private final ModelPaths        paths;
    private final ClipTokenizer     tokenizer;

    /**
     * Encodes a free-text query into a 512-dim L2-normalised CLIP embedding.
     * The text encoder session is loaded lazily on first call and may be evicted via
     * {@link OnnxModelRegistry#evict(String)} between searches.
     */
    public float[] encode(String text) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("clip_text", paths.clipText(), List.of("embedding"));
        int[][]   tokens  = tokenizer.tokenize(text);
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), tokens);
             OrtSession.Result result = session.run(Map.of("text", tensor))) {
            float[][] raw = (float[][]) result.get(0)
                                          .getValue();
            if (raw.length != 1) {
                throw new IllegalArgumentException();
            }
            return l2Normalize(raw[0]);
        }
    }
}
