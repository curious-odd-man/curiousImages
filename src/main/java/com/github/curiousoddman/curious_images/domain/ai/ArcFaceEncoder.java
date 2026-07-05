package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.Map;

import static com.github.curiousoddman.curious_images.domain.ai.ClipTextEncoder.l2Normalize;

/**
 * Wraps the ArcFace R50 ONNX model. Accepts a 112×112 aligned face crop produced by
 * {@link FaceAligner} and returns a 512-dimensional L2-normalised embedding.
 * <p>
 * Preprocessing: RGB channel order, normalise to [-1, 1] via {@code (pixel / 127.5) - 1.0}.
 * Input tensor name: {@code "input.1"} (standard ONNX export from InsightFace buffalo_l).
 */
@Component
@RequiredArgsConstructor
public class ArcFaceEncoder {
    private static final int FACE_SIZE = 112;

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    /**
     * Encodes an aligned 112×112 face crop into a 512-dim L2-normalised embedding.
     *
     * @param alignedFace 112×112 {@link BufferedImage} from {@link FaceAligner#align}
     * @return float[512] L2-normalised embedding
     */
    public float[] encode(BufferedImage alignedFace) throws OrtException {
        OrtSession    session = registry.getOrLoad("arcface", paths.arcFace());
        float[][][][] input   = toTensor(alignedFace);
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input);
             OrtSession.Result result = session.run(Map.of("input", tensor))
        ) {
            float[][] raw = (float[][]) result.get(0)
                                              .getValue();
            if (raw.length != 1) {
                throw new IllegalStateException("Unexpected dimension!");
            }
            return l2Normalize(raw[0]);
        }
    }

    /**
     * Converts a 112×112 {@link BufferedImage} to float[1][3][112][112].
     * Channel order: RGB. Normalisation: {@code (pixel / 127.5f) - 1.0f}.
     */
    private float[][][][] toTensor(BufferedImage img) {
        int[]         pixels = img.getRGB(0, 0, FACE_SIZE, FACE_SIZE, null, 0, FACE_SIZE);
        float[][][][] tensor = new float[1][3][FACE_SIZE][FACE_SIZE];
        for (int y = 0; y < FACE_SIZE; y++) {
            for (int x = 0; x < FACE_SIZE; x++) {
                int rgb = pixels[y * FACE_SIZE + x];
                tensor[0][0][y][x] = (((rgb >> 16) & 0xFF) / 127.5f) - 1.0f; // R
                tensor[0][1][y][x] = (((rgb >> 8) & 0xFF) / 127.5f) - 1.0f; // G
                tensor[0][2][y][x] = ((rgb & 0xFF) / 127.5f) - 1.0f; // B
            }
        }
        return tensor;
    }
}
