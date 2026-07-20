package com.github.curiousoddman.curious_images.model;

public record FaceLandmarks(double leftEyeX, double leftEyeY, double rightEyeX, double rightEyeY, double noseX,
                            double noseY, double leftMouthX, double leftMouthY, double rightMouthX, double rightMouthY) {
}
