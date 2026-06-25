package com.github.curiousoddman.curious_images.domain.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Aligns a detected face to the ArcFace 112×112 canonical reference landmarks using a 2D
 * similarity transform (uniform scale + rotation + translation). The transform is estimated via
 * the Umeyama algorithm (simplified 2-D version) and applied with
 * {@link Graphics2D#drawImage(java.awt.Image, java.awt.geom.AffineTransform, java.awt.image.ImageObserver)}
 * — no OpenCV dependency required.
 */
@Component
@Slf4j
public class FaceAligner {

    /**
     * ArcFace reference 5-point landmarks in 112×112 space (InsightFace convention).
     */
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
     * @param source    the full original image
     * @param landmarks float[5][2] of [x,y] landmark pixel coordinates in {@code source} space
     * @return a 112×112 RGB aligned face crop ready for ArcFace
     */
    public BufferedImage align(Long faceId, BufferedImage source, float[][] landmarks) {
        AffineTransform t   = estimateSimilarityTransform(landmarks, REFERENCE);
        BufferedImage   out = new BufferedImage(OUT_SIZE, OUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D      g   = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, t, null);
        g.dispose();
        return out;
    }

    /**
     * Estimates a similarity transform (scale, rotation, translation) that maps {@code src}
     * landmarks to {@code dst} landmarks using the Umeyama least-squares algorithm (2-D variant).
     * <p>
     * Reference: S. Umeyama, "Least-squares estimation of transformation parameters between
     * two point patterns", IEEE TPAMI 13(4), 1991.
     */
    private AffineTransform estimateSimilarityTransform(float[][] src, float[][] dst) {
        int n = src.length;

        // 1. Compute centroids
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

        // 2. Compute cross-covariance and source variance
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

        // 3. Compute rotation angle from the 2×2 covariance matrix
        //    For similarity: a = (sxx+syy), b = (syx-sxy)
        double a        = sxx + syy;
        double b        = syx - sxy;
        double norm     = Math.sqrt(a * a + b * b);
        double cosTheta = (norm > 1e-10) ? a / norm : 1.0;
        double sinTheta = (norm > 1e-10) ? b / norm : 0.0;

        // 4. Scale
        double scale = (varSrc > 1e-10) ? norm / varSrc : 1.0;

        // 5. Translation: dst_centroid = scale * R * src_centroid + t
        double tx = dstMx - scale * (cosTheta * srcMx - sinTheta * srcMy);
        double ty = dstMy - scale * (sinTheta * srcMx + cosTheta * srcMy);

        // AffineTransform matrix: [m00 m10 m01 m11 m02 m12]
        // x' = m00*x + m01*y + m02
        // y' = m10*x + m11*y + m12
        return new AffineTransform(
                scale * cosTheta,   // m00
                scale * sinTheta,   // m10
                scale * -sinTheta,  // m01
                scale * cosTheta,   // m11
                tx, ty              // m02, m12
        );
    }
}
