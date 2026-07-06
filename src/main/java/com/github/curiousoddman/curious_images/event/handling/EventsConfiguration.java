package com.github.curiousoddman.curious_images.event.handling;

import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;

import java.util.Set;

public class EventsConfiguration {
    static final Set<Class<?>> IGNORE_LOGGING_CLASSES = Set.of(
            BackgroundProcessEvent.class,
            ThumbnailsReadyEvent.class
    );
    static final Set<Class<?>> SKIP_LOG_NO_LISTENERS  = Set.of(
    );

    public boolean shouldSkipLog(Object event) {
        Class<?> currentEventClass = event.getClass();
        return IGNORE_LOGGING_CLASSES.contains(currentEventClass);
    }
}
