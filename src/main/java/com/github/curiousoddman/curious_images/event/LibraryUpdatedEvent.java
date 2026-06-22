package com.github.curiousoddman.curious_images.event;

import org.springframework.context.ApplicationEvent;

public class LibraryUpdatedEvent extends ApplicationEvent {
    public LibraryUpdatedEvent(Object source) {
        super(source);
    }
}
