package com.github.curiousoddman.curious_images.event.handling;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component("applicationEventMulticaster")
public class CustomApplicationEventMulticaster extends SimpleApplicationEventMulticaster {
    private final EventsConfiguration cfg = new EventsConfiguration();

    // Only difference is that we log listeners invocations
    @Override
    public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
        ResolvableType                     type                 = (eventType != null ? eventType : ResolvableType.forInstance(event));
        Executor                           executor             = getTaskExecutor();
        Collection<ApplicationListener<?>> applicationListeners = getApplicationListeners(event, type);

        if (applicationListeners.isEmpty()) {
            if (EventsConfiguration.SKIP_LOG_NO_LISTENERS.contains(event.getClass())) {
                return;
            }
            log.error("🔕 No listeners defined for the event: {}", event.getClass()
                                                                       .getSimpleName());
        } else {
            if (!cfg.shouldSkipLog(event)) {
                log.info("🖖 Handling event: {} ", event.getClass()
                                                       .getSimpleName());
            }
        }
        for (ApplicationListener<?> listener : applicationListeners) {
            if (!cfg.shouldSkipLog(event)) { // Skip logging background process event....
                if (listener instanceof ApplicationListenerMethodAdapter adapter) {
                    log.info("\t- {}", adapter);
                } else {
                    log.info("\t- {}", listener.getClass()
                                               .getSimpleName());
                }
            }

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
