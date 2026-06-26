package com.github.curiousoddman.curious_images.event.payload;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class ProgressBackgroundProcessPayload implements BackgroundProcessPayload {
    private final String    processName;
    private final String    progressDetails;
    private final int       currentProgress;
    private final int       maxProgress;
    private final Exception error;
    private final String    progressText;

    @Override
    public String getProgressText() {
        if (progressText == null) {
            return BackgroundProcessPayload.super.getProgressText();
        }
        return progressText;
    }
}
