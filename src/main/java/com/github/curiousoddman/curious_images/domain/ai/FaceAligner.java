package com.github.curiousoddman.curious_images.domain.ai;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

/**
 * Aligns a detected face to the ArcFace 112×112 canonical reference landmarks using a 2D
 * similarity transform (uniform scale + rotation + translation), estimated via the Umeyama
 * algorithm and applied with {@link Imgproc#warpAffine}. warpAffine only touches the 112×112
 * output pixels it needs (inverse-mapped), regardless of the source image's resolution — much
 * cheaper than rasterizing a full-size Graphics2D transform for a small crop.
 */
@Component
@Slf4j
public class FaceAligner {

    private static final float[][] REFERENCE = {
            {38.29f, 51.69f},
            {73.53f, 51.50f},
            {56.02f, 71.74f},
            {41.55f, 92.37f},
            {70.73f, 92.20f}
    };

    private static final int OUT_SIZE = 112;

    /**
     * Crops and aligns a face from {@code source} using detected landmarks.
     *
     * @param faceId
     * @param source    the full original image (BGR Mat, as produced by AiPipelineJob#loadImageOriented)
     * @param landmarks float[5][2] of [x,y] landmark pixel coordinates in {@code source} space
     * @return a 112×112 aligned face crop (BGR), ready for ArcFaceEncoder. Caller must release().
     */
    public Mat align(Long faceId, Mat source, float[][] landmarks) {
        double[] t = estimateSimilarityTransform(landmarks, REFERENCE);

        Mat affineMat = new Mat(2, 3, CvType.CV_64F);
        affineMat.put(0, 0, t[0], t[1], t[2]);
        affineMat.put(1, 0, t[3], t[4], t[5]);

        Mat out = new Mat();
        Imgproc.warpAffine(source, out, affineMat, new Size(OUT_SIZE, OUT_SIZE),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(0, 0, 0));
        affineMat.release();
        return out;
    }

    /**
     * Estimates a similarity transform (scale, rotation, translation) that maps {@code src}
     * landmarks to {@code dst} landmarks using the Umeyama least-squares algorithm (2-D variant).
     * Returns the six affine coefficients [m00, m01, m02, m10, m11, m12] in the same convention
     * as java.awt.geom.AffineTransform:
     * x' = m00*x + m01*y + m02
     * y' = m10*x + m11*y + m12
     * <p>
     * Reference: S. Umeyama, "Least-squares estimation of transformation parameters between
     * two point patterns", IEEE TPAMI 13(4), 1991.
     */
    private double[] estimateSimilarityTransform(float[][] src, float[][] dst) {
        int n = src.length;

        double srcMx = 0, srcMy = 0, dstMx = 0, dstMy = 0;
        for (int i = 0; i < n; i++) {
            srcMx += src[i][0];
            srcMy += src[i][1];
            dstMx += dst[i][0];
            dstMy += dst[i][1];
        }
        srcMx /= n;
        srcMy /= n;
        dstMx /= n;
        dstMy /= n;

        double sxx    = 0, sxy = 0, syx = 0, syy = 0;
        double varSrc = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[i][0] - srcMx;
            double sy = src[i][1] - srcMy;
            double dx = dst[i][0] - dstMx;
            double dy = dst[i][1] - dstMy;
            sxx += sx * dx;
            sxy += sx * dy;
            syx += sy * dx;
            syy += sy * dy;
            varSrc += sx * sx + sy * sy;
        }
        varSrc /= n;
        sxx /= n;
        sxy /= n;
        syx /= n;
        syy /= n;

        double a        = sxx + syy;
        double b        = syx - sxy;
        double norm     = Math.sqrt(a * a + b * b);
        double cosTheta = (norm > 1e-10) ? a / norm : 1.0;
        double sinTheta = (norm > 1e-10) ? b / norm : 0.0;

        double scale = (varSrc > 1e-10) ? norm / varSrc : 1.0;

        double tx = dstMx - scale * (cosTheta * srcMx - sinTheta * srcMy);
        double ty = dstMy - scale * (sinTheta * srcMx + cosTheta * srcMy);

        return new double[]{
                scale * cosTheta, scale * -sinTheta, tx,   // m00, m01, m02
                scale * sinTheta, scale * cosTheta, ty    // m10, m11, m12
        };
    }
}