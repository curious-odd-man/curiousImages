package com.github.curiousoddman.curious_images.domain.dedupe.hasher;

import lombok.RequiredArgsConstructor;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import org.springframework.stereotype.Component;

// This hasher is not really used. It is here for later if needed - the performance is way worse than the current.
// Run performance manual test with profiling to see and compare performance.
@Component
@RequiredArgsConstructor
public class PcaHasher {
    private final PixelHasher pixelHasher;

    public String hashPca(Mat image) {
        Mat normalized = normalizeOrientation(image);
        try {
            return pixelHasher.hashPixels(normalized);
        } finally {
            if (normalized != image) {
                normalized.release();
            }
        }
    }

    private Mat normalizeOrientation(Mat image) {

        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

        Mat edges = new Mat();
        Imgproc.Canny(gray, edges, 80, 160);

        Moments m = Imgproc.moments(edges, true);

        double angle = 0.5 * Math.atan2(
                2 * m.get_mu11(),
                m.get_mu20() - m.get_mu02());
        Mat rotated = rotate(image, -Math.toDegrees(angle));

        if (brightnessCentroid(rotated).y < rotated.rows() / 2.0) {

            Mat tmp = rotate(rotated, 180);

            rotated.release();
            rotated = tmp;
        }

        gray.release();
        edges.release();

        return rotated;
    }

    private Mat rotate(Mat src, double angleDegrees) {

        Point center = new Point(
                src.cols() / 2.0,
                src.rows() / 2.0);

        Mat matrix =
                Imgproc.getRotationMatrix2D(center, angleDegrees, 1.0);

        RotatedRect box =
                new RotatedRect(
                        center,
                        new Size(src.cols(), src.rows()),
                        angleDegrees);

        Rect bounds = box.boundingRect();

        matrix.put(
                0,
                2,
                matrix.get(0, 2)[0] + bounds.width / 2.0 - center.x);

        matrix.put(
                1,
                2,
                matrix.get(1, 2)[0] + bounds.height / 2.0 - center.y);

        Mat dst = new Mat();

        Imgproc.warpAffine(
                src,
                dst,
                matrix,
                bounds.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE);

        matrix.release();

        return dst;
    }

    private Point brightnessCentroid(Mat image) {

        Mat gray = new Mat();

        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

        double sx  = 0;
        double sy  = 0;
        double sum = 0;

        for (int y = 0; y < gray.rows(); y++) {
            for (int x = 0; x < gray.cols(); x++) {

                double w = gray.get(y, x)[0];

                sx += x * w;
                sy += y * w;
                sum += w;
            }
        }

        gray.release();

        if (sum == 0) {
            return new Point(
                    image.cols() / 2.0,
                    image.rows() / 2.0);
        }

        return new Point(
                sx / sum,
                sy / sum);
    }
}
