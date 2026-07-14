package com.github.curiousoddman.curious_images.domain.dedupe.trfm;

public interface Transformer {

    int x(int x, int y, int w, int h);

    int y(int x, int y, int w, int h);

    default int offset(int x, int y, int width, int height, int channels) {
        return (y(x, y, width, height) * width + x(x, y, width, height)) * channels;
    }

    default boolean swapWH() {
        return false;
    }

    class IdentityTransformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return x;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return y;
        }
    }

    class Rotate90Transformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return y;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return h - 1 - x;
        }

        @Override
        public boolean swapWH() {
            return true;
        }
    }

    class Rotate180Transformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return w - 1 - x;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return h - 1 - y;
        }
    }

    class Rotate270Transformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return w - 1 - y;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return x;
        }

        @Override
        public boolean swapWH() {
            return true;
        }
    }

    class FlipHorizontalTransformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return w - 1 - x;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return y;
        }
    }

    class FlipVerticalTransformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return x;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return h - 1 - y;
        }
    }

    class TransposeTransformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return y;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return x;
        }

        @Override
        public boolean swapWH() {
            return true;
        }
    }

    class TransverseTransformer implements Transformer {

        @Override
        public int x(int x, int y, int w, int h) {
            return w - 1 - y;
        }

        @Override
        public int y(int x, int y, int w, int h) {
            return h - 1 - x;
        }

        @Override
        public boolean swapWH() {
            return true;
        }
    }
}
