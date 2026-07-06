package com.github.curiousoddman.curious_images.event.model;

import org.springframework.context.ApplicationEvent;

public class UserShutdownApplicationEvent extends ApplicationEvent {
    public UserShutdownApplicationEvent(Object source) {
        super(source);
    }
}
