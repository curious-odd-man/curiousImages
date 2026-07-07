package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

@UtilityClass
public class ImageUtils {

    public static BufferedImage rotate90(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        BufferedImage dst = new BufferedImage(h, w, src.getType());

        IntStream.range(0, h)
                 .parallel()
                 .forEach(y -> {
                     for (int x = 0; x < w; x++) {
                         dst.setRGB(h - 1 - y, x, src.getRGB(x, y));
                     }
                 });

        return dst;
    }

    public static BufferedImage rotate180(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        BufferedImage dst = new BufferedImage(w, h, src.getType());

        IntStream.range(0, h)
                 .parallel()
                 .forEach(y -> {
                     for (int x = 0; x < w; x++) {
                         dst.setRGB(w - 1 - x, h - 1 - y, src.getRGB(x, y));
                     }
                 });

        return dst;
    }

    public static BufferedImage rotate270(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        BufferedImage dst = new BufferedImage(h, w, src.getType());

        IntStream.range(0, h)
                 .parallel()
                 .forEach(y -> {
                     for (int x = 0; x < w; x++) {
                         dst.setRGB(y, w - 1 - x, src.getRGB(x, y));
                     }
                 });

        return dst;
    }

    /**
     * Converts an OpenCV {@link Mat} to a {@link BufferedImage}. Handles the common cases:
     * 3-channel BGR (as produced by Imgcodecs.imread/imdecode), 1-channel grayscale, and
     * 4-channel BGRA. Does NOT release the input Mat — caller still owns it.
     */
    public static BufferedImage toBufferedImage(Mat mat) {
        Mat     converted    = mat;
        boolean needsRelease = false;

        int type;
        switch (mat.channels()) {
            case 1 -> type = BufferedImage.TYPE_BYTE_GRAY;
            case 3 -> {
                // BufferedImage.TYPE_3BYTE_BGR expects BGR byte order — which is exactly
                // what imread/imdecode already produce, so no channel swap needed here.
                type = BufferedImage.TYPE_3BYTE_BGR;
            }
            case 4 -> {
                // BufferedImage has no native BGRA byte-order type; convert to ARGB-compatible
                // BGR order first (drop or reorder alpha as needed for your use case).
                converted = new Mat();
                Imgproc.cvtColor(mat, converted, Imgproc.COLOR_BGRA2BGR);
                needsRelease = true;
                type = BufferedImage.TYPE_3BYTE_BGR;
            }
            default -> throw new IllegalArgumentException("Unsupported channel count: " + mat.channels());
        }

        // Ensure 8-bit depth; imread/imdecode with IMREAD_COLOR already gives CV_8UC3, but
        // guard against any upstream Mat that ended up as float/16-bit (e.g. after convertTo).
        if (converted.depth() != CvType.CV_8U) {
            Mat depthConverted = new Mat();
            converted.convertTo(depthConverted, converted.channels() == 1 ? CvType.CV_8UC1 : CvType.CV_8UC3, 255.0);
            if (needsRelease) {
                converted.release();
            }
            converted = depthConverted;
            needsRelease = true;
        }

        int width    = converted.cols();
        int height   = converted.rows();
        int channels = converted.channels();

        byte[] pixelData = new byte[width * height * channels];
        converted.get(0, 0, pixelData);

        BufferedImage image = new BufferedImage(width, height, type);
        byte[] targetData = ((DataBufferByte) image.getRaster()
                                                   .getDataBuffer()).getData();
        System.arraycopy(pixelData, 0, targetData, 0, pixelData.length);

        if (needsRelease) {
            converted.release();
        }
        return image;
    }

    /**
     * Converts a BufferedImage to a BGR Mat (the inverse operation) — useful if you need to feed
     * a BufferedImage from elsewhere in the codebase into a Mat-based pipeline stage.
     */
    public static Mat toMat(BufferedImage image) {
        BufferedImage bgr = image;
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            bgr.getGraphics()
               .drawImage(image, 0, 0, null);
        }
        byte[] pixelData = ((DataBufferByte) bgr.getRaster()
                                                .getDataBuffer()).getData();
        Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, pixelData);
        return mat;
    }

    public static Mat imreadUnicodeSafe(Path path, int flags) throws IOException {
        byte[]    bytes   = Files.readAllBytes(path); // Java handles Unicode paths natively
        MatOfByte encoded = new MatOfByte(bytes);
        Mat       img     = Imgcodecs.imdecode(encoded, flags);
        encoded.release();
        return img; // caller checks .empty(), same as before
    }

    public static float[] blobToFloatArray(Mat blob, int expectedLength) {
        Mat     flatView = blob.reshape(1, 1); // 4D -> 2D (1 row, N cols), same underlying data
        float[] flat     = new float[expectedLength];
        int     copied   = flatView.get(0, 0, flat);
        if (copied != expectedLength * Float.BYTES && copied != expectedLength) {
            // Mat.get's returned byte/element count convention varies by overload/version —
            // this check just catches the "silently got way less than expected" case.
            throw new IllegalStateException(
                    "Blob extraction copied unexpected amount: " + copied + ", expected " + expectedLength);
        }
        return flat;
    }

    /**
     * Loads an image via OpenCV's native codecs (fast — libjpeg-turbo/libpng backed) and
     * applies EXIF-orientation correction. Returns a BGR Mat (OpenCV's native channel order —
     * downstream encoders must account for this, e.g. via swapRB in blobFromImage).
     * <p>
     * Caller owns the returned Mat and MUST call {@code .release()} on it when done.
     */
    public static Mat loadImageOriented(String absolutePath, Integer orientation) throws IOException {
        Mat img = imreadUnicodeSafe(Path.of(absolutePath), Imgcodecs.IMREAD_COLOR);
        if (img.empty()) {
            throw new IOException("OpenCV could not decode: " + absolutePath);
        }
        rotateForOrientation(img, orientation);
        return img;
    }

    /**
     * In-place rotation to match the stored orientation (clockwise degrees — see
     * ThumbnailGenerator#rotate / PhotoMetadataExtractor's EXIF Orientation mapping, the only
     * producer of this value). Mirrors ThumbnailGenerator.rotate's normalization exactly.
     */
    private static void rotateForOrientation(Mat img, Integer degrees) {
        if (degrees == null) {
            return;
        }
        int normalized = ((degrees % 360) + 360) % 360;
        switch (normalized) {
            case 90 -> Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE);
            case 180 -> Core.rotate(img, img, Core.ROTATE_180);
            case 270 -> Core.rotate(img, img, Core.ROTATE_90_COUNTERCLOCKWISE);
            default -> { /* 0, or any non-90/180/270 value: no-op, same as original rotate() */ }
        }
    }
}
