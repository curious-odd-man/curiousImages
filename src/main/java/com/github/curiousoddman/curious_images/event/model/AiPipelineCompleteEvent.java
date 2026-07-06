package com.github.curiousoddman.curious_images.event.model;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code AiPipelineJob} when all pipeline stages complete successfully.
 * Consumed by {@code AlbumGenerationService} (triggers album rebuild) and
 * {@code LibraryController} (refreshes the Albums tree section).
 */
public class AiPipelineCompleteEvent extends ApplicationEvent {
    public AiPipelineCompleteEvent(Object source) {
        super(source);
    }
}
