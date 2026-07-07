package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;

import java.awt.image.BufferedImage;
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
}
