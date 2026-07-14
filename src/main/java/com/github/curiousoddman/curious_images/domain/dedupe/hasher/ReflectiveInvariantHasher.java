package com.github.curiousoddman.curious_images.domain.dedupe.hasher;

import com.github.curiousoddman.curious_images.domain.dedupe.trfm.Transformer;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import static com.github.curiousoddman.curious_images.domain.dedupe.hasher.PixelHasher.sha256;

// This hasher is not really used. It is here for later if needed - the performance is way worse than the current.
// Run performance manual test with profiling to see and compare performance.
public class ReflectiveInvariantHasher {

    public static String hashRotationAndMirrorInvariant(Mat image) {
        int width    = image.cols();
        int height   = image.rows();
        int channels = image.channels();

        byte[] pixels = new byte[width * height * channels];
        image.get(0, 0, pixels);

        return Stream.of(
                             new Transformer.IdentityTransformer(),
                             new Transformer.Rotate90Transformer(),
                             new Transformer.Rotate180Transformer(),
                             new Transformer.Rotate270Transformer(),
                             new Transformer.FlipHorizontalTransformer(),
                             new Transformer.FlipVerticalTransformer(),
                             new Transformer.TransposeTransformer(),
                             new Transformer.TransverseTransformer()
                     )
                     .map(t -> hash(image, t, pixels))
                     .min(String::compareTo)
                     .orElseThrow();
    }

    private static String hash(Mat image, Transformer transformer, byte[] pixels) {
        int width    = image.cols();
        int height   = image.rows();
        int channels = image.channels();

        boolean swap = transformer.swapWH();

        int outWidth  = swap ? height : width;
        int outHeight = swap ? width : height;

        MessageDigest digest = sha256();

        // Canonical header
        ByteBuffer header = ByteBuffer.allocate(8)
                                      .order(ByteOrder.BIG_ENDIAN);

        header.putInt(Math.max(width, height));
        header.putInt(Math.min(width, height));

        digest.update(header.array());

        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int off = transformer.offset(x, y, width, height, channels);
                digest.update(pixels, off, channels);
            }
        }

        return HexFormat.of()
                        .formatHex(digest.digest());
    }
}
