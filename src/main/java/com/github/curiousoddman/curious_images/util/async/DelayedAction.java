package com.github.curiousoddman.curious_images.util.async;

import lombok.RequiredArgsConstructor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
public class DelayedAction {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final long     delay;
    private final TimeUnit timeUnit;


    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "delayed-action-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> pendingSave = null;

    public void reSchedule(Runnable runnable) {
        if (pendingSave != null) {
            pendingSave.cancel(false);
        }

        pendingSave = scheduler.schedule(runnable, delay, timeUnit);
    }
}
