package com.github.curiousoddman.curious_images.event;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component("applicationEventMulticaster")
public class CustomApplicationEventMulticaster extends SimpleApplicationEventMulticaster {
    private ApplicationEvent previousEvent;

    private static final Set<Class<?>> EXCLUSIONS = Set.of(
    );

    // Only difference is that we log listeners invocations
    @Override
    public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
        ResolvableType                     type                 = (eventType != null ? eventType : ResolvableType.forInstance(event));
        Executor                           executor             = getTaskExecutor();
        Collection<ApplicationListener<?>> applicationListeners = getApplicationListeners(event, type);

        Class<?> currentEventClass = event.getClass();
        boolean isProgressEvent = currentEventClass.equals(BackgroundProcessEvent.class)
                || previousEvent == null
                || !previousEvent.getClass()
                                 .equals(currentEventClass);

        if (applicationListeners.isEmpty()) {
            if (EXCLUSIONS.contains(event.getClass())) {
                return;
            }
            log.error("🔕 No listeners defined for the event: {}", event.getClass()
                                                                       .getSimpleName());
        } else {
            if (!isProgressEvent) {
                log.info("🖖 Handling event: {} ", event.getClass()
                                                       .getSimpleName());
            }
        }
        for (ApplicationListener<?> listener : applicationListeners) {
            if (!isProgressEvent) { // Skip logging background process event....
                if (listener instanceof ApplicationListenerMethodAdapter adapter) {
                    log.info("\t- {}", adapter);
                } else {
                    log.info("\t- {}", listener.getClass()
                                               .getSimpleName());
                }
            }
            previousEvent = event;

            if (executor != null && listener.supportsAsyncExecution()) {
                try {
                    executor.execute(() -> invokeListener(listener, event));
                } catch (RejectedExecutionException ex) {
                    // Probably on shutdown -> invoke listener locally instead
                    invokeListener(listener, event);
                }
            } else {
                invokeListener(listener, event);
            }
        }
    }
}
