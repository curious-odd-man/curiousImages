package com.github.curiousoddman.curious_images.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ImageUtilsTest {
    @Test
    void rotate90FourTimesReturnsOriginal() {
        BufferedImage original = createRandomImage(317, 241);

        BufferedImage rotated = original;
        for (int i = 0; i < 4; i++) {
            rotated = ImageUtils.rotate90(rotated);
        }

        assertImagesEqual(original, rotated);
    }

    @Test
    void rotate180TwiceReturnsOriginal() {
        BufferedImage original = createRandomImage(317, 241);

        BufferedImage rotated = ImageUtils.rotate90(original);
        rotated = ImageUtils.rotate90(rotated); // 180°
        rotated = ImageUtils.rotate90(rotated);
        rotated = ImageUtils.rotate90(rotated); // another 180°

        assertImagesEqual(original, rotated);
    }

    @Test
    void rotate270Then90ReturnsOriginal() {
        BufferedImage original = createRandomImage(317, 241);

        BufferedImage rotated = original;

        // 270°
        rotated = ImageUtils.rotate90(rotated);
        rotated = ImageUtils.rotate90(rotated);
        rotated = ImageUtils.rotate90(rotated);

        // +90° = 360°
        rotated = ImageUtils.rotate90(rotated);

        assertImagesEqual(original, rotated);
    }

    @Test
    void dimensionsAreSwappedAfter90DegreeRotation() {
        BufferedImage original = createRandomImage(317, 241);

        BufferedImage rotated = ImageUtils.rotate90(original);

        assertEquals(original.getHeight(), rotated.getWidth());
        assertEquals(original.getWidth(), rotated.getHeight());
    }

    private static BufferedImage createRandomImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        Random random = new Random(12345);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }

        return image;
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());

        int w = expected.getWidth();
        int h = expected.getHeight();

        int[] expectedPixels = expected.getRGB(0, 0, w, h, null, 0, w);
        int[] actualPixels   = actual.getRGB(0, 0, w, h, null, 0, w);

        assertArrayEquals(expectedPixels, actualPixels);
    }
}