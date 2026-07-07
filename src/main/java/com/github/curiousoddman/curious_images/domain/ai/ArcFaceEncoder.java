package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
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

    private static final OrtEnvironment ENV = OrtEnvironment.getEnvironment();

    private static final long[] INPUT_SHAPE = {1, 3, FACE_SIZE, FACE_SIZE};
    private static final int    NUM_FLOATS  = 3 * FACE_SIZE * FACE_SIZE;
    public static final  float  SCALE       = 1f / 127.5f;

    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(NUM_FLOATS * Float.BYTES)
                                                      .order(ByteOrder.nativeOrder())
                                                      .asFloatBuffer();

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    /**
     * Encodes an aligned 112×112 face crop into a 512-dim L2-normalised embedding.
     *
     * @param alignedFace 112×112 {@link BufferedImage} from {@link FaceAligner#align}
     * @return float[512] L2-normalised embedding
     */
    public float[] encode(BufferedImage alignedFace) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("arcface", paths.arcFace(), List.of("output"));

        inputBuffer.clear();
        toTensor(alignedFace, inputBuffer);
        inputBuffer.position(NUM_FLOATS);
        inputBuffer.flip();

        try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, inputBuffer, INPUT_SHAPE);
             OrtSession.Result result = session.run(Map.of("input", tensor))) {

            float[][] raw = (float[][]) result.get(0)
                                              .getValue();

            if (raw.length != 1) {
                throw new IllegalStateException("Unexpected dimension!");
            }

            return l2Normalize(raw[0]);
        }
    }

    private void toTensor(BufferedImage img, FloatBuffer buffer) {
        int[] pixels = ((DataBufferInt) img.getRaster()
                                           .getDataBuffer()).getData();

        int plane = FACE_SIZE * FACE_SIZE;

        for (int i = 0; i < plane; i++) {
            int rgb = pixels[i];
            buffer.put(i, (((rgb >>> 16) & 0xFF) * SCALE) - 1.0f);
            buffer.put(plane + i, (((rgb >>> 8) & 0xFF) * SCALE) - 1.0f);
            buffer.put(2 * plane + i, ((rgb & 0xFF) * SCALE) - 1.0f);
        }
    }
}
