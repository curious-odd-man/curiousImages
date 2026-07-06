package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.payload.EndedBackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.payload.IndeterminateBackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.payload.ProgressBackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.COMPLETED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.FAILED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.INTERRUPTED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.INTERRUPT_REQUESTED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.NEVER_RUN;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.RUNNING;

@Slf4j
public abstract class BackgroundJob {
    private static final int PROGRESS_PUBLISH_INTERVAL_MS = 100;

    @Getter
    private volatile JobStatus jobStatus             = NEVER_RUN;
    private volatile long      lastProgressPublishMs = 0;

    protected ApplicationEventPublisher eventPublisher;

    public abstract String getProcessName();

    /**
     * Whether {@code JobManager} should treat this job class as supersedable: a newer submission
     * of the same class drops any not-yet-started queued instance and requests interruption of a
     * currently-running one, rather than being discarded as a duplicate. Default {@code false} —
     * no behavior change for existing job classes. See {@code ThumbnailGenerationJob} for the
     * motivating case (implementation plan §5).
     */
    public boolean isSupersedable() {
        return false;
    }

    public void run(ApplicationEventPublisher applicationEventPublisher) {
        if (isInterruptRequested()) {
            log.info("Job interrupted before running");
            jobStatus = INTERRUPTED;
            return;
        }
        eventPublisher = applicationEventPublisher;
        try {
            log.info("Set running...");
            jobStatus = RUNNING;
            lastProgressPublishMs = 0;
            runImpl();
            if (jobStatus == INTERRUPT_REQUESTED) {
                log.info("Job interrupted...");
                jobStatus = INTERRUPTED;
            } else {
                log.info("Job completed");
                jobStatus = COMPLETED;
            }
        } catch (Exception e) {
            log.info("Job failed");
            jobStatus = FAILED;
        }
    }

    public abstract void runImpl() throws Exception;

    public boolean isRunning() {
        return jobStatus == RUNNING;
    }

    public void requestInterrupt() {
        jobStatus = INTERRUPT_REQUESTED;
    }

    /**
     * The job's run loop should poll this periodically (e.g. once per item, or once per
     * completed hashing task) and stop promptly once it flips to {@code true}.
     */
    public boolean isInterruptRequested() {
        return jobStatus == INTERRUPT_REQUESTED || jobStatus == INTERRUPTED;
    }

    protected void publishStarted(String description) {
        IndeterminateBackgroundProcessPayload payload = indeterminatePayloadBuilder()
                .progressDetails(description)
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.STARTED,
                        payload
                )
        );
    }

    /**
     * Unthrottled — use for one-off milestones (e.g. "discovered N files", or the initial
     * "0 of N done" after preloading cached results) rather than per-item progress, which should
     * go through {@link #publishProgress} instead.
     */
    protected void publishInProgress(String description, int progress, int maxProgress) {
        ProgressBackgroundProcessPayload payload = progressPayloadBuilder()
                .progressDetails(description)
                .currentProgress(progress)
                .maxProgress(maxProgress)
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.IN_PROGRESS,
                        payload
                )
        );
    }

    /**
     * Throttled per-item progress publish: only actually publishes if
     * {@value #PROGRESS_PUBLISH_INTERVAL_MS}ms have passed since the last publish, or
     * {@code isLastItem} is {@code true}. Synchronized so it's safe to call from multiple worker
     * threads concurrently (e.g. a parallel hashing pool reporting as each task completes).
     */
    protected synchronized void publishProgress(String phaseText, int progress, int maxProgress,
                                                String currentItem, boolean isLastItem) {
        long nowMs = System.currentTimeMillis();
        if (!isLastItem && nowMs - lastProgressPublishMs < PROGRESS_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastProgressPublishMs = nowMs;

        ProgressBackgroundProcessPayload payload = progressPayloadBuilder()
                .progressDetails(currentItem)
                .currentProgress(progress)
                .maxProgress(maxProgress)
                .progressText(phaseText + ": " + progress + "/" + maxProgress)
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.IN_PROGRESS,
                        payload
                )
        );
    }

    protected void publishInterrupted() {
        EndedBackgroundProcessPayload payload = endedPayloadBuilder()
                .progressDetails("Interrupted")
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.INTERRUPTED,
                        payload
                )
        );
    }

    protected void publishFailed(Exception e) {
        EndedBackgroundProcessPayload payload = endedPayloadBuilder()
                .progressDetails(" failed: " + e.getMessage())
                .error(e)
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.FAILED,
                        payload
                )
        );
    }

    protected void publishEnded(String description) {
        EndedBackgroundProcessPayload payload = endedPayloadBuilder()
                .progressDetails(description)
                .build();

        eventPublisher.publishEvent(
                new BackgroundProcessEvent(
                        this,
                        BackgroundProcessEventType.ENDED,
                        payload
                )
        );
    }

    private IndeterminateBackgroundProcessPayload.IndeterminateBackgroundProcessPayloadBuilder indeterminatePayloadBuilder() {
        return IndeterminateBackgroundProcessPayload
                .builder()
                .processName(getProcessName());
    }

    private ProgressBackgroundProcessPayload.ProgressBackgroundProcessPayloadBuilder progressPayloadBuilder() {
        return ProgressBackgroundProcessPayload
                .builder()
                .processName(getProcessName());
    }

    private EndedBackgroundProcessPayload.EndedBackgroundProcessPayloadBuilder endedPayloadBuilder() {
        return EndedBackgroundProcessPayload
                .builder()
                .processName(getProcessName());
    }
}
