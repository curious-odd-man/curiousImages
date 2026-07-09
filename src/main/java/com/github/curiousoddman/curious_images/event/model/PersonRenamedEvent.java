package com.github.curiousoddman.curious_images.event.model;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class PersonRenamedEvent extends ApplicationEvent {
    private final long   personId;
    private final String newName;

    public PersonRenamedEvent(Object source, long personId, String newName) {
        super(source);
        this.personId = personId;
        this.newName = newName;
    }
}
