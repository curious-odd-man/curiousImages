package com.github.curiousoddman.curious_images.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code PersonClusteringService} after the clustering pass finishes and person
 * rows have been written. Consumed by {@code LibraryController} to refresh the Persons section
 * of the library tree.
 */
public class PersonsUpdatedEvent extends ApplicationEvent {
    public PersonsUpdatedEvent(Object source) {
        super(source);
    }
}
