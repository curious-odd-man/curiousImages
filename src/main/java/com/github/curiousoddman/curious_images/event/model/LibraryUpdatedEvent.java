package com.github.curiousoddman.curious_images.event.model;

import org.springframework.context.ApplicationEvent;

public class LibraryUpdatedEvent extends ApplicationEvent {
    public LibraryUpdatedEvent(Object source) {
        super(source);
    }
}
