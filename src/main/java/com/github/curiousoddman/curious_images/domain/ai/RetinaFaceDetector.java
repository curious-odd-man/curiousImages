package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
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

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    /**
     * Detects faces in {@code image}. Returns a list of detected faces; empty if none found
     * or if the model is not available.
     */
    public List<DetectedFace> detect(BufferedImage image) throws OrtException, IrrecoverableIterationException {
        OrtSession session = registry.getOrLoad("retinaface_r50", paths.retinaFace());

        int origW = image.getWidth();
        int origH = image.getHeight();

        // 1. Resize to INPUT_SIZE × INPUT_SIZE
        PreprocessedImage preprocessedImage = resizeAndPad(image, INPUT_SIZE);

        // 2. Convert to float[1][3][H][W], BGR, mean-subtract
        float[][][][] input = toTensor(preprocessedImage.image());

        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input);
             OrtSession.Result result = session.run(Map.of("input", tensor))) {
            // Outputs: [0] classifications (conf), [1] bounding boxes, [2] landmarks
            float[][][] clsRaw = (float[][][]) result.get("confidence")
                                                     .get()
                                                     .getValue();
            float[][][] bboxRaw = (float[][][]) result.get("bbox")
                                                      .get()
                                                      .getValue();
            float[][][] ldmRaw = (float[][][]) result.get("landmark")
                                                     .get()
                                                     .getValue();

            List<float[]> anchors = generateAnchors(INPUT_SIZE, INPUT_SIZE);

            if (clsRaw.length != 1 || bboxRaw.length != 1 || ldmRaw.length != 1) {
                throw new IllegalStateException("Unexpected dimension size" + clsRaw.length + "|" + bboxRaw.length + "|" + ldmRaw.length);
            }
            return decodeAndFilter(clsRaw[0], bboxRaw[0], ldmRaw[0], anchors, preprocessedImage, origW, origH);
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

        float scale = Math.min(
                (float) targetSize / origW,
                (float) targetSize / origH
        );

        int resizedW = Math.round(origW * scale);
        int resizedH = Math.round(origH * scale);

        BufferedImage resized;
        try {
            resized = Thumbnails.of(src)
                                .size(resizedW, resizedH)
                                .asBufferedImage();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        BufferedImage padded = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = padded.createGraphics();

        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, targetSize, targetSize);

            int padX = (targetSize - resizedW) / 2;
            int padY = (targetSize - resizedH) / 2;

            g.drawImage(resized, padX, padY, null);

            return new PreprocessedImage(
                    padded,
                    scale,
                    padX,
                    padY,
                    resizedW,
                    resizedH
            );
        } finally {
            g.dispose();
        }
    }

    /**
     * Converts a 640×640 {@link BufferedImage} to a float[1][3][H][W] tensor in BGR order
     * with per-channel mean subtraction.
     */
    private float[][][][] toTensor(BufferedImage img) {
        int           h      = img.getHeight();
        int           w      = img.getWidth();
        float[][][][] tensor = new float[1][3][h][w];
        int[]         pixels = img.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int   rgb = pixels[y * w + x];
                float r   = ((rgb >> 16) & 0xFF);
                float g   = ((rgb >> 8) & 0xFF);
                float b   = (rgb & 0xFF);
                tensor[0][0][y][x] = b - MEAN[0]; // B channel
                tensor[0][1][y][x] = g - MEAN[1]; // G channel
                tensor[0][2][y][x] = r - MEAN[2]; // R channel
            }
        }
        return tensor;
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
