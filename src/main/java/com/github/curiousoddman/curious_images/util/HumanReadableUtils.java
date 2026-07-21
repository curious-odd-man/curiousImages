package com.github.curiousoddman.curious_images.util;

public class HumanReadableUtils {
    public static String size(Long bytes) {
        if (bytes == null) {
            return "unknown";
        }
        double   size      = bytes;
        String[] units     = {"B", "KB", "MB", "GB"};
        int      unitIndex = 0;
        while (size >= 1024.0 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    public static String rate(double d) {
        return String.format("%.2f", d);
    }

    public static String gps(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return "Unknown location";
        }

        String latDirection = latitude >= 0 ? "N" : "S";
        String lonDirection = longitude >= 0 ? "E" : "W";

        return String.format(
                "%.6f° %s, %.6f° %s",
                Math.abs(latitude),
                latDirection,
                Math.abs(longitude),
                lonDirection
        );
    }
}
