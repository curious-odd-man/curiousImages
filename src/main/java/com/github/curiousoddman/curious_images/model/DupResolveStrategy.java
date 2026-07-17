package com.github.curiousoddman.curious_images.model;

public enum DupResolveStrategy {
    KEEP_CHECKED,
    REMOVE_CHECKED,
    KEEP_ALL,
    REMOVE_ALL;

    public static boolean isDropped(DupResolveStrategy strategy, boolean isChecked) {
        return switch (strategy) {
            case KEEP_ALL -> false;
            case KEEP_CHECKED -> !isChecked;
            case REMOVE_CHECKED -> isChecked;
            case REMOVE_ALL -> true;
        };
    }
}
