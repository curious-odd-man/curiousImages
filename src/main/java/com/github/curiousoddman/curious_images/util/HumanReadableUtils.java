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
}
