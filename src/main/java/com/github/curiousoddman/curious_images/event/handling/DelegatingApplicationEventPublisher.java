package com.github.curiousoddman.curious_images.event.handling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class DelegatingApplicationEventPublisher implements ApplicationEventPublisher {
    private final EventsConfiguration cfg = new EventsConfiguration();
    private final ApplicationContext  context;

    @Override
    public void publishEvent(ApplicationEvent event) {
        logEvent(event);
        context.publishEvent(event);
    }

    @Override
    public void publishEvent(Object event) {
        logEvent(event);
        context.publishEvent(event);
    }

    private void logEvent(Object event) {
        Class<?> currentEventClass = event.getClass();
        if (cfg.shouldSkipLog(event)) {
            // Skip logging background process event....
            return;
        }
        log.info("📨 Published: {} by {}",
                currentEventClass.getSimpleName(),
                event instanceof ApplicationEvent ae
                        ? ae.getSource()
                            .getClass()
                            .getSimpleName()
                        : "unknown");
    }
}

