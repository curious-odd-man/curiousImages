package com.github.curiousoddman.curious_images.event;

import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import lombok.Builder;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
@Builder
public class BackgroundProcessEvent extends ApplicationEvent {
    // TODO: refactor this and improve progress bar - clear property names, progress bar instead of indicator, clear progress messages from all processes
    private final Object                     source;        // Duplicate this for @Builder to work
    private final String                     processName;
    private final String                     description;
    private final int                        progress;
    private final int                        maxProgress;
    private final Exception                  error;
    private final BackgroundProcessEventType eventType;
    private final String                     currentItem;   // absolute path of the file currently being processed; nullable

    public BackgroundProcessEvent(Object source, String processName, String description, int progress, int maxProgress, Exception error, BackgroundProcessEventType eventType, String currentItem) {
        super(source);
        this.source = source;
        this.processName = processName;
        this.description = description;
        this.progress = progress;
        this.maxProgress = maxProgress;
        this.error = error;
        this.eventType = eventType;
        this.currentItem = currentItem;
    }
}