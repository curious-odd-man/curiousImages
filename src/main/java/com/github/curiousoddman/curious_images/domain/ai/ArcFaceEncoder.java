package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.util.EmbeddingMath.l2Normalize;
import static com.github.curiousoddman.curious_images.util.ImageUtils.blobToFloatArray;

/**
 * Wraps the ArcFace R50 ONNX model. Accepts a 112×112 aligned face crop (BGR Mat) produced by
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
     * Encodes an aligned 112×112 BGR face crop into a 512-dim L2-normalised embedding.
     *
     * @param alignedFace 112×112 BGR {@link Mat} from {@link FaceAligner#align}
     * @return float[512] L2-normalised embedding
     */
    public float[] encode(Mat alignedFace) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("arcface", paths.arcFace(), List.of("683"));

        // blobFromImage does resize(no-op here, already 112x112) + mean-subtract + scale +
        // BGR->RGB swap + HWC->NCHW layout in one native call.
        Mat blob = Dnn.blobFromImage(
                alignedFace,
                SCALE,
                new Size(FACE_SIZE, FACE_SIZE),
                new Scalar(127.5, 127.5, 127.5),
                true,   // swapRB: BGR -> RGB
                false   // crop
        );

        float[] flat = blobToFloatArray(blob, NUM_FLOATS);
        inputBuffer.clear();
        inputBuffer.put(flat);
        inputBuffer.flip();
        blob.release();

        try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, inputBuffer, INPUT_SHAPE);
             OrtSession.Result result = session.run(Map.of("input.1", tensor))) {

            float[][] raw = (float[][]) result.get(0)
                                              .getValue();
            if (raw.length != 1) {
                throw new IllegalStateException("Unexpected dimension!");
            }
            return l2Normalize(raw[0]);
        }
    }
}