package com.github.curiousoddman.curious_images.event.model;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code AiPipelineJob} at the end of a successful pipeline run to trigger
 * automatic album generation. Consumed by {@code AlbumGenerationService}.
 */
public class RegenerateAlbumsEvent extends ApplicationEvent {
    public RegenerateAlbumsEvent(Object source) {
        super(source);
    }
}
