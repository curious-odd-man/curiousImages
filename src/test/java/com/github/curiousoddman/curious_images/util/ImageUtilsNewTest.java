package com.github.curiousoddman.curious_images.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Set;

class ImageUtilsNewTest {

    static final List<Duration> old90Perf  = new ArrayList<>();
    static final List<Duration> new90Perf  = new ArrayList<>();
    static final List<Duration> new290Perf = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(Path.of("D:\\My Pictures\\Foto\\Я\\Я в детстве"), new FileCollectingVisitor(Set.of("jpg", "jpeg", "png", "cr2"), found));

        for (Path path : found) {
            BufferedImage bufferedImage = ImageIO.read(path.toFile());

            measure(() -> ImageUtils.rotate90(bufferedImage), old90Perf);
           // measure(() -> ImageUtilsNew.rotate90(bufferedImage), new90Perf);
        }

        System.out.println("rotate 90");
        System.out.println(fmt(old90Perf, "old"));
        System.out.println(fmt(new90Perf, "new"));
        System.out.println(fmt(new290Perf, "new2"));
    }

    private static String fmt(List<Duration> perf, String number) {
        LongSummaryStatistics stat = perf.stream()
                                         .mapToLong(Duration::toNanos)
                                         .summaryStatistics();
        return "\n\t" + number + ": " + stat.toString();
    }

    public static void measure(Runnable runnable, List<Duration> list) {
        Instant start = Instant.now();
        runnable.run();
        Instant end = Instant.now();
        list.add(Duration.between(start, end));
    }
}
