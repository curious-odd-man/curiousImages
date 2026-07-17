package com.github.curiousoddman.curious_images.event.model;

import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class TreeViewUpdateEvent extends ApplicationEvent {
    private final TreeViewUpdatePayload payload;

    public TreeViewUpdateEvent(Object source, TreeViewUpdatePayload payload) {
        super(source);
        this.payload = payload;
    }
}
