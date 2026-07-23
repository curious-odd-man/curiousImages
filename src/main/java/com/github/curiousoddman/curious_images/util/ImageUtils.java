package com.github.curiousoddman.curious_images.util;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageUtils {
    private static final String CR2_EXTENSION = "cr2";

    private final PhotoMetadataExtractor metadataExtractor;

    public Optional<Mat> imageOrCr2Preview(MediaPhotoRecord MediaPhotoRecord) {
        int orientation = Objects.requireNonNullElse(MediaPhotoRecord.getOrientation(), 0);
        return imageOrCr2Preview(Path.of(MediaPhotoRecord.getAbsolutePath()), MediaPhotoRecord.getExtension(), orientation);
    }

    /**
     * @return the decoded image, or empty if nothing decodable was found (corrupt file, CR2 with
     * no usable embedded preview, etc.) — never throws for a single bad file.
     * NOTE:
     * - CR2 file preview jpeg has same orientation as raw image, but metadata extractor only reads jpeg bytes, without orientation - therefore manually rotate image
     * - imreadUnicodeSafe reads whole file instead - therefore it produces already rotated Mat
     */
    public Optional<Mat> imageOrCr2Preview(Path sourceFile, String extension, int rotationDegreeForCr2) {
        try {
            if (CR2_EXTENSION.equalsIgnoreCase(extension)) {
                return metadataExtractor.extractEmbeddedPreviewBytes(sourceFile)
                                        .map(ImageUtils::decodeBytes)
                                        .map(mat -> rotateForOrientation(mat, rotationDegreeForCr2));
            }
            Mat img = imreadRotated(sourceFile);
            boolean empty = img.empty();
            if (empty) {
                log.warn("Unable to load image or cr2 preview: {}", sourceFile);
            }
            return empty ? Optional.empty() : Optional.of(img);
        } catch (Exception e) {
            log.warn("Failed to decode {}", sourceFile, e);
            return Optional.empty();
        }
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
            case 3 -> // BufferedImage.TYPE_3BYTE_BGR expects BGR byte order — which is exactly
                // what imread/imdecode already produce, so no channel swap needed here.
                    type = BufferedImage.TYPE_3BYTE_BGR;
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
    public static Mat loadImageOriented(String absolutePath) throws IOException {
        Mat img = imreadRotated(Path.of(absolutePath));
        if (img.empty()) {
            throw new IOException("OpenCV could not decode: " + absolutePath);
        }
        return img;
    }

    private static Mat rotateForOrientation(Mat img, Integer degrees) {
        if (degrees == null) {
            return img;
        }
        int normalized = ((degrees % 360) + 360) % 360;
        switch (normalized) {
            case 90 -> Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE);
            case 180 -> Core.rotate(img, img, Core.ROTATE_180);
            case 270 -> Core.rotate(img, img, Core.ROTATE_90_COUNTERCLOCKWISE);
            default -> { /* 0, or any non-90/180/270 value: no-op, same as original rotate() */ }
        }
        return img;
    }

    /**
     * This method returns rotated image per orientation metadata
     */
    private static Mat imreadRotated(Path path) throws IOException {
        byte[]    bytes   = Files.readAllBytes(path); // Java handles Unicode paths natively
        MatOfByte encoded = new MatOfByte(bytes);
        Mat       img     = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        encoded.release();
        return img; // caller checks .empty(), same as before
    }

    private static Mat decodeBytes(byte[] bytes) {
        Mat encoded = new MatOfByte(bytes);
        Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        encoded.release();
        if (decoded.empty()) {
            log.warn("Failed to decode embedded preview bytes");
            return null;
        }
        return decoded;
    }
}
