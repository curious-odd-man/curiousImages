package com.github.curiousoddman.curious_images.util;

import com.github.curiousoddman.curious_images.domain.ai.PersonClusteringService;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import lombok.experimental.UtilityClass;

import java.util.Collection;

/**
 * Small vector-math helpers shared by {@link PersonClusteringService} (automatic clustering) and
 * {@link PersonCorrectionService} (manual FR1–FR5 corrections). Both need the exact same
 * dot-product / L2-normalisation / centroid-averaging semantics so that a cluster's centroid means
 * the same thing regardless of whether it was last touched by an algorithm or a human click.
 */
@UtilityClass
public final class EmbeddingMath {
    public static float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-10) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    /**
     * Averages a non-empty collection of vectors and L2-normalises the result. This is the
     * "recompute from scratch" centroid — used whenever a cluster's membership changes (FR8),
     * as opposed to {@link #incrementalUpdate}, which nudges an existing centroid by one member
     * without re-reading every other member.
     *
     * @throws IllegalArgumentException if {@code vectors} is empty — callers must delete the
     *                                  cluster row instead of computing an average-of-nothing
     *                                  (see {@code ClusterRepository#deleteQuery}).
     */
    public static float[] average(Collection<float[]> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot compute a centroid for an empty member set — delete the cluster instead");
        }
        int dim = vectors.iterator()
                         .next().length;
        float[] centroid = new float[dim];
        for (float[] v : vectors) {
            for (int k = 0; k < dim; k++) {
                centroid[k] += v[k];
            }
        }
        float scale = 1.0f / vectors.size();
        for (int k = 0; k < dim; k++) {
            centroid[k] *= scale;
        }
        l2Normalize(centroid);
        return centroid;
    }

    /**
     * Incremental centroid update: moves {@code centroid} (in place) toward {@code v} by one
     * step, weighted by {@code oldSize}, and re-normalises. Used by the incremental clustering
     * fast-path (FR7) where re-reading every existing member on every new face would defeat the
     * point of staying cheap.
     */
    public static void incrementalUpdate(float[] centroid, int oldSize, float[] v) {
        int   newSize = oldSize + 1;
        float scale   = 1.0f / newSize;
        for (int k = 0; k < centroid.length; k++) {
            centroid[k] = (centroid[k] * oldSize + v[k]) * scale;
        }
        l2Normalize(centroid);
    }
}
