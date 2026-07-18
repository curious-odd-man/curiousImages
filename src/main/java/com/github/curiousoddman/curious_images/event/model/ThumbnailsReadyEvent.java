package com.github.curiousoddman.curious_images.event.model;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

import java.util.Set;

@Getter
@ToString
public class ThumbnailsReadyEvent extends ApplicationEvent {
    private final Set<Long> photoIds;

    public ThumbnailsReadyEvent(Object source, Set<Long> photoIds) {
        super(source);
        this.photoIds = photoIds;
    }
}
