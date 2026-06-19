package com.github.curiousoddman.curious_images.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class RescanLibraryEvent extends ApplicationEvent {
    private final String path;

    public RescanLibraryEvent(Object source, String path) {
        super(source);
        this.path = path;
    }
}
