package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.util.EmbeddingMath.l2Normalize;
import static com.github.curiousoddman.curious_images.util.ImageUtils.blobToFloatArray;

/**
 * Wraps the CLIP ViT-B/32 image encoder ONNX model. Accepts a BGR {@link Mat}, returns a
 * 512-dimensional L2-normalised embedding.
 * <p>
 * Preprocessing mirrors the Python reference:
 * <ol>
 *   <li>Resize shortest edge to 224, bicubic.</li>
 *   <li>Centre-crop to 224×224.</li>
 *   <li>Normalise per channel: {@code (pixel/255 - mean) / std}, RGB order.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ClipImageEncoder {

    private static final int    CLIP_SIZE = 224;
    private static final Scalar MEAN      = new Scalar(0.48145466, 0.4578275, 0.40821073);
    private static final Scalar STD       = new Scalar(0.26862954, 0.26130258, 0.27577711);

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    public float[] encode(Mat image) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("clip_image", paths.clipImage(), List.of("embedding"));

        Mat resized = resizeShortestEdge(image, CLIP_SIZE);
        Mat cropped = centreCrop(resized, CLIP_SIZE, CLIP_SIZE);
        resized.release();

        // BGR -> RGB, uint8 -> float32 [0,1]
        Mat rgb = new Mat();
        Imgproc.cvtColor(cropped, rgb, Imgproc.COLOR_BGR2RGB);
        cropped.release();

        Mat floatMat = new Mat();
        rgb.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 255.0);
        rgb.release();

        // Per-channel (pixel - mean) / std, vectorized across the whole Mat at once.
        Core.subtract(floatMat, MEAN, floatMat);
        Core.divide(floatMat, STD, floatMat);

        // HWC -> NCHW via blobFromImage (scalefactor=1, mean=0: we already normalised above)
        Mat blob = Dnn.blobFromImage(floatMat, 1.0, new Size(CLIP_SIZE, CLIP_SIZE),
                new Scalar(0, 0, 0), false, false);
        floatMat.release();

        int numFloats = 3 * CLIP_SIZE * CLIP_SIZE;
        float[] flat = blobToFloatArray(blob, numFloats);
        blob.release();

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), java.nio.FloatBuffer.wrap(flat),
                new long[]{1, 3, CLIP_SIZE, CLIP_SIZE});
             OrtSession.Result result = session.run(Map.of("image", tensor))) {

            float[][] raw = (float[][]) result.get(0)
                                              .getValue();
            if (raw.length != 1) {
                throw new IllegalStateException("Unexpected dimension!");
            }
            return l2Normalize(raw[0]);
        }
    }

    private Mat resizeShortestEdge(Mat src, int size) {
        int w = src.cols(), h = src.rows();
        int newW, newH;
        if (w < h) {
            newW = size;
            newH = h * size / w;
        } else {
            newH = size;
            newW = w * size / h;
        }
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(newW, newH), 0, 0, Imgproc.INTER_CUBIC);
        return dst;
    }

    private Mat centreCrop(Mat src, int tw, int th) {
        int x = Math.max(0, (src.cols() - tw) / 2);
        int y = Math.max(0, (src.rows() - th) / 2);
        int w = Math.min(tw, src.cols());
        int h = Math.min(th, src.rows());
        // Rect view is a shallow reference into src's data — cheap, but means the returned Mat
        // is only valid as long as `src` isn't released first. We release cropped explicitly
        // above before releasing resized/src, so this is safe as written.
        return new Mat(src, new Rect(x, y, w, h));
    }
}