package com.github.curiousoddman.curious_images.util.async;

import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared run-state and event-publishing machinery for long-running background jobs (import scan,
 * duplicate detection, ...). Extracted from the original {@code ImportService}, which had this
 * logic embedded directly. A subclass gets, for free:
 * <ul>
 *     <li>A single-flight guard ({@link #tryStart()} / {@link #finish()}) so a second invocation
 *     while a run is already in progress is rejected rather than started concurrently.</li>
 *     <li>A cooperative interrupt flag ({@link #requestInterrupt()} / {@link #isInterruptRequested()}).
 *     Nothing here pre-empts a running thread — the job's own loop must poll it.</li>
 *     <li>{@link BackgroundProcessEvent} publishing for each lifecycle stage, tagged with this
 *     job's {@code processName} so the UI can tell jobs apart.</li>
 *     <li>Throttled per-item progress publishing ({@link #publishProgress}) so a tight loop
 *     doesn't flood the event bus — safe to call concurrently from a worker pool.</li>
 * </ul>
 * <p>
 * Cancellation note: {@link #requestInterrupt()} is meant to be called from a job-agnostic
 * {@code @EventListener} on {@code InterruptBackgroundProcessEvent}, exactly as
 * {@code ImportService} did before this extraction — that event carries no job identifier, so it
 * interrupts whichever job(s) happen to be running. Fine as long as this application never
 * intentionally runs two background jobs at once; worth revisiting if that ever changes.
 */
public abstract class AbstractBackgroundJob {

    private static final int PROGRESS_PUBLISH_INTERVAL_MS = 100;

    private final    AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean       shouldInterrupt;
    private volatile long          lastProgressPublishMs;

    protected abstract ApplicationEventPublisher eventPublisher();

    protected abstract String getProcessName();

    /**
     * Attempts to claim the single-flight slot for this job. Returns {@code false} (and changes
     * nothing) if a run is already in progress — the caller should log and return without
     * starting a thread. On success, resets the interrupt flag and progress throttle so the new
     * run starts clean.
     */
    protected boolean tryStart() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        shouldInterrupt = false;
        lastProgressPublishMs = 0;
        return true;
    }

    /**
     * Releases the single-flight slot. Must be called from a {@code finally} block around the
     * job's run method, on every exit path (success, interrupt, or failure).
     */
    protected void finish() {
        running.set(false);
    }

    /**
     * Requests cooperative cancellation. Call this from an {@code @EventListener} on
     * {@code InterruptBackgroundProcessEvent}. No-op if no run is in progress.
     */
    public void requestInterrupt() {
        shouldInterrupt = true;
    }

    /**
     * The job's run loop should poll this periodically (e.g. once per item, or once per
     * completed hashing task) and stop promptly once it flips to {@code true}.
     */
    protected boolean isInterruptRequested() {
        return shouldInterrupt;
    }

    protected void publishStarted(String description) {
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.STARTED)
                .description(description)
                .maxProgress(-1)
                .build());
    }

    /**
     * Unthrottled — use for one-off milestones (e.g. "discovered N files", or the initial
     * "0 of N done" after preloading cached results) rather than per-item progress, which should
     * go through {@link #publishProgress} instead.
     */
    protected void publishInProgress(String description, int progress, int maxProgress) {
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.IN_PROGRESS)
                .description(description)
                .progress(progress)
                .maxProgress(maxProgress)
                .build());
    }

    /**
     * Throttled per-item progress publish: only actually publishes if
     * {@value #PROGRESS_PUBLISH_INTERVAL_MS}ms have passed since the last publish, or
     * {@code isLastItem} is {@code true}. Synchronized so it's safe to call from multiple worker
     * threads concurrently (e.g. a parallel hashing pool reporting as each task completes).
     */
    protected synchronized void publishProgress(String description, int progress, int maxProgress,
                                                String currentItem, boolean isLastItem) {
        long nowMs = System.currentTimeMillis();
        if (!isLastItem && nowMs - lastProgressPublishMs < PROGRESS_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastProgressPublishMs = nowMs;
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.IN_PROGRESS)
                .description(description)
                .progress(progress)
                .maxProgress(maxProgress)
                .currentItem(currentItem)
                .build());
    }

    protected void publishInterrupted(String description) {
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.INTERRUPTED)
                .description(description)
                .build());
    }

    protected void publishFailed(Exception e) {
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.FAILED)
                .description(getProcessName() + " failed: " + e.getMessage())
                .error(e)
                .build());
    }

    protected void publishEnded(String description) {
        eventPublisher().publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.ENDED)
                .description(description)
                .build());
    }

    private BackgroundProcessEvent.BackgroundProcessEventBuilder eventBuilder() {
        return BackgroundProcessEvent.builder()
                                     .source(this)
                                     .processName(getProcessName());
    }
}
