package com.github.curiousoddman.curious_images.event.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BackgroundProcessEventType {
    STARTED(false),
    IN_PROGRESS(false),
    INTERRUPTED(true),
    FAILED(true),
    ENDED(true);

    private final boolean isTerminal;
}
