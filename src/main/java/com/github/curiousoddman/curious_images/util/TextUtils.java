package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TextUtils {
    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
