package com.github.curiousoddman.curious_images.event.payload;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class EndedBackgroundProcessPayload implements BackgroundProcessPayload {
    private final String    processName;
    private final String    progressDetails;
    private final int       currentProgress = 1;
    private final int       maxProgress     = 1;
    private final Throwable error;
}
