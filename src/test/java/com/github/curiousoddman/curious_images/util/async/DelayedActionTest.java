package com.github.curiousoddman.curious_images.util.async;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

class DelayedActionTest {

    @Test
    void shouldExecuteRunnableAfterDelay() {
        DelayedAction delayedAction = new DelayedAction(200, TimeUnit.MILLISECONDS);

        AtomicBoolean executed = new AtomicBoolean(false);
        long          start    = System.nanoTime();

        delayedAction.reSchedule(() -> executed.set(true));

        await()
                .atMost(Duration.ofSeconds(1))
                .untilTrue(executed);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= 200);
    }

    @Test
    void shouldCancelPreviousActionWhenRescheduled() {
        DelayedAction delayedAction = new DelayedAction(200, TimeUnit.MILLISECONDS);

        AtomicInteger counter = new AtomicInteger();

        delayedAction.reSchedule(counter::incrementAndGet);

        await().during(Duration.ofMillis(100))
               .until(() -> true);

        delayedAction.reSchedule(counter::incrementAndGet);

        await()
                .atMost(Duration.ofSeconds(1))
                .untilAtomic(counter, is(equalTo(1)));

        assertEquals(1, counter.get());
    }

    @Test
    void shouldExecuteOnlyLatestScheduledAction() {
        DelayedAction delayedAction = new DelayedAction(200, TimeUnit.MILLISECONDS);

        AtomicBoolean firstExecuted  = new AtomicBoolean(false);
        AtomicBoolean secondExecuted = new AtomicBoolean(false);

        delayedAction.reSchedule(() -> firstExecuted.set(true));

        await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(50))
                .during(Duration.ofMillis(100))
                .until(() -> true);

        delayedAction.reSchedule(() -> secondExecuted.set(true));

        await()
                .atMost(Duration.ofSeconds(1))
                .untilTrue(secondExecuted);

        assertFalse(firstExecuted.get());
        assertTrue(secondExecuted.get());
    }

    @Test
    void shouldAllowReschedulingAfterExecution() {
        DelayedAction delayedAction = new DelayedAction(150, TimeUnit.MILLISECONDS);

        AtomicInteger counter = new AtomicInteger();

        delayedAction.reSchedule(counter::incrementAndGet);

        await()
                .atMost(Duration.ofSeconds(1))
                .untilAtomic(counter, is(equalTo(1)));

        delayedAction.reSchedule(counter::incrementAndGet);

        await()
                .atMost(Duration.ofSeconds(1))
                .untilAtomic(counter, is(equalTo(2)));

        assertEquals(2, counter.get());
    }

    @Test
    void multipleRapidReschedulesShouldOnlyExecuteLast() {
        DelayedAction delayedAction = new DelayedAction(200, TimeUnit.MILLISECONDS);

        AtomicInteger counter = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            delayedAction.reSchedule(counter::incrementAndGet);
        }

        await()
                .atMost(Duration.ofSeconds(1))
                .untilAtomic(counter, is(equalTo(1)));

        assertEquals(1, counter.get());
    }
}