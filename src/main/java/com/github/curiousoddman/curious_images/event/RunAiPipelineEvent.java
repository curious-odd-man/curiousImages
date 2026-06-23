package com.github.curiousoddman.curious_images.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code ImportService} after a successful import scan completes, after it has
 * already published {@link LibraryUpdatedEvent}. Consumed by {@code AiPipelineJob} to trigger
 * the face detection / embedding / CLIP / Lucene pipeline.
 */
public class RunAiPipelineEvent extends ApplicationEvent {
    public RunAiPipelineEvent(Object source) {
        super(source);
    }
}
