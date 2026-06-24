package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;

import static com.github.curiousoddman.curious_images.domain.ai.ClipTextEncoder.l2Normalize;

/**
 * Wraps the CLIP ViT-B/32 image encoder ONNX model. Accepts any {@link BufferedImage}, returns
 * a 512-dimensional L2-normalised embedding.
 * <p>
 * Preprocessing mirrors the Python reference:
 * <ol>
 *   <li>Resize shortest edge to 224, bicubic.</li>
 *   <li>Centre-crop to 224×224.</li>
 *   <li>Normalise per channel: {@code (pixel/255 - mean) / std}, RGB order.</li>
 * </ol>
 * CLIP ViT-B/32 normalisation constants: mean = [0.48145466, 0.4578275, 0.40821073],
 * std = [0.26862954, 0.26130258, 0.27577711].
 */
@Component
@RequiredArgsConstructor
public class ClipImageEncoder {

    private static final int     CLIP_SIZE = 224;
    private static final float[] MEAN      = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD       = {0.26862954f, 0.26130258f, 0.27577711f};

    private final OnnxModelRegistry registry;
    private final ModelPaths        paths;

    /**
     * Encodes {@code image} into a 512-dim L2-normalised CLIP embedding.
     */
    public float[] encode(BufferedImage image) throws OrtException {
        OrtSession    session = registry.getOrLoad("clip_image", paths.clipImage());
        float[][][][] input   = preprocess(image);
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input);
             OrtSession.Result result = session.run(Map.of("image", tensor))
        ) {
            float[][] raw = (float[][]) result.get(0)
                                              .getValue();
            if (raw.length != 1) {
                throw new IllegalStateException("Unexpected dimension!");
            }
            return l2Normalize(raw[0]);
        }
    }

    private float[][][][] preprocess(BufferedImage image) {
        // Resize shortest edge to 224, preserve ratio
        BufferedImage resized = resizeShortestEdge(image, CLIP_SIZE);
        // Centre-crop to 224×224
        BufferedImage cropped = centreCrop(resized, CLIP_SIZE, CLIP_SIZE);

        int[]         pixels = cropped.getRGB(0, 0, CLIP_SIZE, CLIP_SIZE, null, 0, CLIP_SIZE);
        float[][][][] tensor = new float[1][3][CLIP_SIZE][CLIP_SIZE];
        for (int y = 0; y < CLIP_SIZE; y++) {
            for (int x = 0; x < CLIP_SIZE; x++) {
                int   rgb = pixels[y * CLIP_SIZE + x];
                float r   = ((rgb >> 16) & 0xFF) / 255f;
                float g   = ((rgb >> 8) & 0xFF) / 255f;
                float b   = (rgb & 0xFF) / 255f;
                tensor[0][0][y][x] = (r - MEAN[0]) / STD[0];
                tensor[0][1][y][x] = (g - MEAN[1]) / STD[1];
                tensor[0][2][y][x] = (b - MEAN[2]) / STD[2];
            }
        }
        return tensor;
    }

    private BufferedImage resizeShortestEdge(BufferedImage src, int size) {
        int w = src.getWidth(), h = src.getHeight();
        int newW, newH;
        if (w < h) {
            newW = size;
            newH = h * size / w;
        } else {
            newH = size;
            newW = w * size / h;
        }
        try {
            return Thumbnails.of(src)
                             .forceSize(newW, newH)
                             .asBufferedImage();
        } catch (IOException e) {
            throw new RuntimeException("Failed to resize image for CLIP", e);
        }
    }

    private BufferedImage centreCrop(BufferedImage src, int tw, int th) {
        int x = (src.getWidth() - tw) / 2;
        int y = (src.getHeight() - th) / 2;
        return src.getSubimage(Math.max(0, x), Math.max(0, y),
                Math.min(tw, src.getWidth()), Math.min(th, src.getHeight()));
    }

}
