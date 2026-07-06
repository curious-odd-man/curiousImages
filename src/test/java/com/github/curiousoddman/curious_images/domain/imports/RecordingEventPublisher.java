package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal {@link ApplicationEventPublisher} test double that just records every published event.
 * Used instead of a real Spring {@code ApplicationContext} so {@code ImportService} tests stay
 * fast and don't need to bootstrap the JavaFX/Spring desktop-app context — see
 * {@code AbstractRepositoryH2Test} for the same reasoning applied to the persistence layer.
 */
class RecordingEventPublisher implements ApplicationEventPublisher {
    private final List<ApplicationEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publishEvent(Object event) {
        if (event instanceof ApplicationEvent applicationEvent) {
            events.add(applicationEvent);
        }
    }

    List<BackgroundProcessEvent> backgroundProcessEvents() {
        synchronized (events) {
            return events.stream()
                         .filter(BackgroundProcessEvent.class::isInstance)
                         .map(BackgroundProcessEvent.class::cast)
                         .toList();
        }
    }
}
