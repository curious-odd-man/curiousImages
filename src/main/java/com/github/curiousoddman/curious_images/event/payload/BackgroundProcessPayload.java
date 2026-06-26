package com.github.curiousoddman.curious_images.event.payload;

public interface BackgroundProcessPayload {

    String getProcessName();

    default boolean hasProgress() {
        return getMaxProgress() > 0;
    }

    int getCurrentProgress();

    int getMaxProgress();

    default double getProgressNormalized() {
        return (double) getCurrentProgress() / getMaxProgress();
    }

    default String getProgressText() {
        int currentProgress = getCurrentProgress();
        int maxProgress     = getMaxProgress();
        return currentProgress + "/" + maxProgress;
    }

    String getProgressDetails();

    Exception getError();

    default boolean hasError() {
        return getError() != null;
    }
}
