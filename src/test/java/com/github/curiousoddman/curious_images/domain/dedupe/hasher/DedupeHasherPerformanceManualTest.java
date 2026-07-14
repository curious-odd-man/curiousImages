package com.github.curiousoddman.curious_images.domain.dedupe.hasher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.github.curiousoddman.curious_images.domain.ai.RetinaFaceDetectorTest.collectAllImages;

public class DedupeHasherPerformanceManualTest {
    private static final PixelHasher     pixelHasher     = new PixelHasher(new ImageUtils(new PhotoMetadataExtractor(new ObjectMapper())));
    private static final PcaHasher       pcaHasher       = new PcaHasher(pixelHasher);
    private static final InvariantHasher invariantHasher = new InvariantHasher();

    private static final Set<String> pixelHash      = new HashSet<>();
    private static final Set<String> pcaHash        = new HashSet<>();
    private static final Set<String> invariantHash  = new HashSet<>();
    private static final Set<String> reflectiveHash = new HashSet<>();

    public static void main(String[] args) throws IOException {
        OpenCV.loadLocally();

        List<File> files = collectAllImages();

        for (File file : files) {
            Mat mat = ImageUtils.loadImageOriented(file.getAbsolutePath());

            boolean b1 = pixelHash.add(pixelHasher.hashPixels(mat));
            boolean b2 = pcaHash.add(pcaHasher.hashPca(mat));
            boolean b3 = invariantHash.add(invariantHasher.hashRotationInvariant(mat));
            boolean b4 = reflectiveHash.add(ReflectiveInvariantHasher.hashRotationAndMirrorInvariant(mat));

            boolean ok = (b1 && b2 && b3 && b4) || (!b1 && !b2 && !b3 && !b4);

            System.out.println(
                    "%s %s %s %s %s %s".formatted(
                            q(ok),
                            q(b1), q(b2), q(b3), q(b4),
                            file
                    )
            );
        }
    }

    private static String q(boolean ok) {
        return ok ? "🟢" : "🔴";
    }
}
