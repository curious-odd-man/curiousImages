package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps the RetinaFace MobileNet0.25 ONNX model. Accepts a {@link BufferedImage}, returns
 * detected faces with bounding boxes (normalised [0,1]) and 5-point landmarks (pixel coords).
 * <p>
 * Preprocessing: resize to 640×640, BGR channel order, subtract per-channel mean [104,117,123].
 * The model produces three output tensors per anchor: classifications, bounding boxes, landmarks.
 * Anchors are generated for strides [8, 16, 32] with 2 anchors per cell (standard MBN0.25 config).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetinaFaceDetector {

    private static final float NMS_THRESHOLD = 0.4f;

    private static final int     INPUT_SIZE           = 1200;
    private static final float   CONFIDENCE_THRESHOLD = 0.8f;  // R50 is more confident; raise threshold
    private static final float[] MEAN                 = {104f, 117f, 123f}; // BGR — unchanged

    // Anchor configuration for MobileNet0.25: strides [8,16,32], 2 anchors per cell
    private static final int[]   STRIDES       = {8, 16, 32};
    private static final float[] MIN_SIZES_S8  = {16f, 32f};
    private static final float[] MIN_SIZES_S16 = {64f, 128f};
    private static final float[] MIN_SIZES_S32 = {256f, 512f};

    private static final OrtEnvironment ENV = OrtEnvironment.getEnvironment();

    private static final long[] INPUT_SHAPE = {1, 3, INPUT_SIZE, INPUT_SIZE};
    private static final int    NUM_FLOATS  = 3 * INPUT_SIZE * INPUT_SIZE;

    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(NUM_FLOATS * Float.BYTES)
                                                      .order(ByteOrder.nativeOrder())
                                                      .asFloatBuffer();

    private final List<float[]> anchors = generateAnchors(INPUT_SIZE, INPUT_SIZE);

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    /**
     * Detects faces in {@code image}. Returns a list of detected faces; empty if none found
     * or if the model is not available.
     */
    public List<DetectedFace> detect(BufferedImage image) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("retinaface_r50", paths.retinaFace(), List.of("bbox", "confidence", "landmark"));

        PreprocessedImage preprocessed = resizeAndPad(image, INPUT_SIZE);

        inputBuffer.clear();
        toTensor(preprocessed.image(), inputBuffer);
        inputBuffer.position(NUM_FLOATS);
        inputBuffer.flip();

        try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, inputBuffer, INPUT_SHAPE);
             OrtSession.Result result = session.run(Map.of("input", tensor))) {

            float[][][] clsRaw = (float[][][]) result.get(1)
                                                     .getValue();
            float[][][] bboxRaw = (float[][][]) result.get(0)
                                                      .getValue();
            float[][][] ldmRaw = (float[][][]) result.get(2)
                                                     .getValue();

            return decodeAndFilter(
                    clsRaw[0],
                    bboxRaw[0],
                    ldmRaw[0],
                    anchors,
                    preprocessed,
                    image.getWidth(),
                    image.getHeight());
        }
    }

    // ── Preprocessing ─────────────────────────────────────────────────────────

    private record PreprocessedImage(
            BufferedImage image,
            float scale,
            int padX,
            int padY,
            int resizedWidth,
            int resizedHeight
    ) {}

    private PreprocessedImage resizeAndPad(BufferedImage src, int targetSize) {
        int origW = src.getWidth();
        int origH = src.getHeight();

        float scale    = Math.min((float) targetSize / origW, (float) targetSize / origH);
        int   resizedW = Math.round(origW * scale);
        int   resizedH = Math.round(origH * scale);

        BufferedImage padded = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D    g      = padded.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, targetSize, targetSize);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int padX = (targetSize - resizedW) / 2;
            int padY = (targetSize - resizedH) / 2;
            g.drawImage(src, padX, padY, resizedW, resizedH, null);

            return new PreprocessedImage(padded, scale, padX, padY, resizedW, resizedH);
        } finally {
            g.dispose();
        }
    }

    private void toTensor(BufferedImage img, FloatBuffer buffer) {
        int w = img.getWidth();
        int h = img.getHeight();

        int[] pixels = ((DataBufferInt) img.getRaster()
                                           .getDataBuffer()).getData();

        int plane = w * h;

        for (int i = 0; i < plane; i++) {

            int rgb = pixels[i];

            buffer.put(i, (rgb & 0xFF) - MEAN[0]);
            buffer.put(plane + i, ((rgb >>> 8) & 0xFF) - MEAN[1]);
            buffer.put(2 * plane + i, ((rgb >>> 16) & 0xFF) - MEAN[2]);
        }
    }

    // ── Anchor generation ─────────────────────────────────────────────────────

    /**
     * Generates the anchor grid for strides [8, 16, 32], 2 anchors per cell, producing
     * (H/8 * W/8 + H/16 * W/16 + H/32 * W/32) * 2 anchors total.
     * Each anchor is [cx, cy, w, h] normalised to [0,1] relative to INPUT_SIZE.
     */
    private List<float[]> generateAnchors(int imgH, int imgW) {
        List<float[]> anchors       = new ArrayList<>();
        float[][]     strideConfigs = {MIN_SIZES_S8, MIN_SIZES_S16, MIN_SIZES_S32};

        for (int si = 0; si < STRIDES.length; si++) {
            int     stride   = STRIDES[si];
            float[] minSizes = strideConfigs[si];
            int     featH    = (int) Math.ceil((double) imgH / stride);
            int     featW    = (int) Math.ceil((double) imgW / stride);
            for (int y = 0; y < featH; y++) {
                for (int x = 0; x < featW; x++) {
                    for (float minSize : minSizes) {
                        float cx = (x + 0.5f) * stride / imgW;
                        float cy = (y + 0.5f) * stride / imgH;
                        float aw = minSize / imgW;
                        float ah = minSize / imgH;
                        anchors.add(new float[]{cx, cy, aw, ah});
                    }
                }
            }
        }

        return anchors;
    }

    // ── Decode & NMS ──────────────────────────────────────────────────────────

    private List<DetectedFace> decodeAndFilter(float[][] cls, float[][] bbox, float[][] ldm,
                                               List<float[]> anchors, PreprocessedImage preprocessedImage,
                                               int origW, int origH) {
        List<DetectedFace> candidates = new ArrayList<>();

        if (anchors.size() != cls.length) {
            throw new IllegalStateException(
                    "Anchor count mismatch. anchors=" + anchors.size() + ", outputs=" + cls.length
            );
        }

        for (int i = 0; i < anchors.size() && i < cls.length; i++) {
            // cls output is [num_anchors][2]; index 1 is face probability
            float conf = cls[i].length >= 2 ? cls[i][1] : cls[i][0];
            if (conf < CONFIDENCE_THRESHOLD) {
                continue;
            }

            float[] a  = anchors.get(i);
            float   ax = a[0], ay = a[1], aw = a[2], ah = a[3];

            BoundingBox result = getBoundingBox(preprocessedImage, origW, origH, ax, aw, ay, ah, bbox[i]);
            float       scale  = preprocessedImage.scale();

            // Decode 5-point landmarks (pixel coords in original image space)
            float[][] landmarks = new float[5][2];
            for (int p = 0; p < 5; p++) {
                float lx = (ax + ldm[i][p * 2] * 0.1f * aw) * INPUT_SIZE;
                float ly = (ay + ldm[i][p * 2 + 1] * 0.1f * ah) * INPUT_SIZE;

                lx = (lx - preprocessedImage.padX()) / scale;
                ly = (ly - preprocessedImage.padY()) / scale;

                landmarks[p][0] = Math.max(0, Math.min(origW, lx));
                landmarks[p][1] = Math.max(0, Math.min(origH, ly));
            }

            // Normalise bbox to [0,1] relative to original image dimensions
            candidates.add(new DetectedFace(
                    result.x1() / origW, result.y1() / origH,
                    result.bw() / origW, result.bh() / origH,
                    conf, landmarks));
        }

        return nms(candidates);
    }

    private static BoundingBox getBoundingBox(PreprocessedImage preprocessedImage, int origW, int origH, float ax, float aw, float ay, float ah, float[] bbox) {
        // Decode bounding box (variance [0.1, 0.2] per InsightFace convention)
        float cx = ax + bbox[0] * 0.1f * aw;
        float cy = ay + bbox[1] * 0.1f * ah;
        float w  = aw * (float) Math.exp(bbox[2] * 0.2f);
        float h  = ah * (float) Math.exp(bbox[3] * 0.2f);

        float x1 = (cx - w / 2f) * INPUT_SIZE;
        float y1 = (cy - h / 2f) * INPUT_SIZE;
        float x2 = (cx + w / 2f) * INPUT_SIZE;
        float y2 = (cy + h / 2f) * INPUT_SIZE;

        float scale = preprocessedImage.scale();
        x1 = (x1 - preprocessedImage.padX()) / scale;
        y1 = (y1 - preprocessedImage.padY()) / scale;
        x2 = (x2 - preprocessedImage.padX()) / scale;
        y2 = (y2 - preprocessedImage.padY()) / scale;

        x1 = Math.max(0, Math.min(origW, x1));
        y1 = Math.max(0, Math.min(origH, y1));
        x2 = Math.max(0, Math.min(origW, x2));
        y2 = Math.max(0, Math.min(origH, y2));

        float bw = x2 - x1;
        float bh = y2 - y1;
        return new BoundingBox(x1, y1, bw, bh);
    }

    private record BoundingBox(float x1, float y1, float bw, float bh) {}

    /**
     * Greedy NMS over detected-face candidates.
     */
    private List<DetectedFace> nms(List<DetectedFace> candidates) {
        candidates.sort((a, b) -> Float.compare(b.confidence(), a.confidence()));
        List<DetectedFace> kept       = new ArrayList<>();
        boolean[]          suppressed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) {
                continue;
            }
            kept.add(candidates.get(i));
            for (int j = i + 1; j < candidates.size(); j++) {
                if (!suppressed[j] && iou(candidates.get(i), candidates.get(j)) > NMS_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    private float iou(DetectedFace a, DetectedFace b) {
        float x1    = Math.max(a.x(), b.x());
        float y1    = Math.max(a.y(), b.y());
        float x2    = Math.min(a.x() + a.w(), b.x() + b.w());
        float y2    = Math.min(a.y() + a.h(), b.y() + b.h());
        float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float union = a.w() * a.h() + b.w() * b.h() - inter;
        return union <= 0 ? 0 : inter / union;
    }
}
