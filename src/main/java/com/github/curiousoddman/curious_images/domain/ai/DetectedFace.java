package com.github.curiousoddman.curious_images.domain.ai;

/**
 * A single detected face.
 *
 * @param x          left edge, normalised [0,1] relative to original image width
 * @param y          top edge,  normalised [0,1] relative to original image height
 * @param w          width,     normalised [0,1] relative to original image width
 * @param h          height,    normalised [0,1] relative to original image height
 * @param confidence face detection confidence score
 * @param landmarks  5×2 array of [x,y] landmark pixel coordinates in original image space
 */
public record DetectedFace(float x, float y, float w, float h,
                           float confidence, float[][] landmarks) {}
