package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.COMPLETED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.FAILED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.INTERRUPTED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.INTERRUPT_REQUESTED;
import static com.github.curiousoddman.curious_images.util.async.jobs.JobStatus.NEVER_RUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link BackgroundJob}.
 * <p>
 * Uses a minimal concrete subclass ({@link RecordingJob}) so we can exercise the
 * protected publish* helpers and control runImpl() behavior per test.
 */
class BackgroundJobTest {

    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
    }

    // ---------------------------------------------------------------
    // Status lifecycle
    // ---------------------------------------------------------------

    @Test
    void initialStatusIsCompleted() {
        RecordingJob job = new RecordingJob(() -> {});
        assertThat(job.getJobStatus()).isEqualTo(NEVER_RUN);
        assertThat(job.isRunning()).isFalse();
    }

    @Test
    void runSetsStatusToCompletedOnSuccess() {
        RecordingJob job = new RecordingJob(() -> {});
        job.run(eventPublisher);
        assertThat(job.getJobStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void runSetsStatusToRunningWhileExecuting() {
        AtomicBoolean jobRunning = new AtomicBoolean(false);
        Runnable[]    inner      = new Runnable[1];
        RecordingJob job = new RecordingJob(() -> {
            inner[0].run();
        });
        inner[0] = () -> jobRunning.set(job.isRunning());
        job.run(eventPublisher);
        // after run() completes it should no longer report running
        assertThat(jobRunning.get()).isTrue();
        assertThat(job.isRunning()).isFalse();
    }

    @Test
    void runSetsStatusToFailedWhenRunImplThrows() {
        RecordingJob job = new RecordingJob(() -> {
            throw new RuntimeException("boom");
        });
        job.run(eventPublisher);
        assertThat(job.getJobStatus()).isEqualTo(FAILED);
    }

    @Test
    void runSetsStatusToFailedWhenRunImplThrowsCheckedException() {
        RecordingJob job = new RecordingJob(() -> {
            throw new java.io.IOException("io boom");
        });
        job.run(eventPublisher);
        assertThat(job.getJobStatus()).isEqualTo(FAILED);
    }

    @Test
    void runSetsStatusToInterruptedWhenInterruptWasRequestedDuringExecution() {
        RecordingJob interruptingJob = new RecordingJob(null);
        interruptingJob.setAction(interruptingJob::requestInterrupt);

        interruptingJob.run(eventPublisher);

        assertThat(interruptingJob.getJobStatus()).isEqualTo(INTERRUPTED);
    }

    @Test
    void requestInterruptSetsStatusToInterruptRequested() {
        RecordingJob job = new RecordingJob(() -> {});
        job.requestInterrupt();
        assertThat(job.getJobStatus()).isEqualTo(INTERRUPT_REQUESTED);
    }

    @Test
    void isInterruptRequestedTrueForInterruptRequestedAndInterruptedStates() {
        RecordingJob job = new RecordingJob(() -> {});

        job.requestInterrupt();
        assertThat(job.isInterruptRequested()).isTrue();

        // simulate run() promoting INTERRUPT_REQUESTED -> INTERRUPTED
        RecordingJob interruptingJob = new RecordingJob(null);
        interruptingJob.setAction(interruptingJob::requestInterrupt);
        interruptingJob.run(eventPublisher);
        assertThat(interruptingJob.isInterruptRequested()).isTrue();
    }

    @Test
    void isInterruptRequestedFalseForOtherStates() {
        RecordingJob job = new RecordingJob(() -> {});
        assertThat(job.isInterruptRequested()).isFalse(); // COMPLETED initially

        job.run(eventPublisher);
        assertThat(job.isInterruptRequested()).isFalse(); // COMPLETED after run
    }

    @Test
    void lastProgressPublishResetsOnEachRun() {
        // First run publishes throttled progress once, sleeps briefly, second run's first
        // progress publish should not be suppressed by state left over from the first run.
        RecordingJob job = new RecordingJob(null);
        job.setAction(() -> job.callPublishProgress("phase", 1, 10, "item", false));
        job.run(eventPublisher);
        job.run(eventPublisher);

        // two runs => two STARTED-equivalent progress publishes (lastProgressPublishMs reset to 0
        // at the start of each run, so each run's first progress call always publishes)
        verify(eventPublisher, times(2)).publishEvent(any(BackgroundProcessEvent.class));
    }

    // ---------------------------------------------------------------
    // Event publishing
    // ---------------------------------------------------------------

    @Test
    void publishStartedPublishesStartedEvent() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishStarted("starting up");

        ArgumentCaptor<BackgroundProcessEvent> captor = ArgumentCaptor.forClass(BackgroundProcessEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()
                         .getEventType()).isEqualTo(BackgroundProcessEventType.STARTED);
    }

    @Test
    void publishInProgressPublishesInProgressEventUnthrottled() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishInProgress("found 5 files", 0, 5);
        job.callPublishInProgress("found 6 files", 0, 6);

        // unthrottled: both calls should publish even though they happen back to back
        verify(eventPublisher, times(2)).publishEvent(any(BackgroundProcessEvent.class));
    }

    @Test
    void publishProgressThrottledThrottlesRapidCalls() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;
        // lastProgressPublishMs defaults to 0 at construction, but run() resets it; here we call
        // the helper directly so the first call always publishes (0ms baseline).

        job.callPublishProgress("phase", 1, 10, "item-1", false);
        job.callPublishProgress("phase", 2, 10, "item-2", false); // within 100ms -> suppressed

        verify(eventPublisher, times(1)).publishEvent(any(BackgroundProcessEvent.class));
    }

    @Test
    void publishProgressThrottledAlwaysPublishesWhenIsLastItemTrue() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishProgress("phase", 1, 10, "item-1", false);
        job.callPublishProgress("phase", 10, 10, "item-10", true); // forced despite throttle window

        verify(eventPublisher, times(2)).publishEvent(any(BackgroundProcessEvent.class));
    }

    @Test
    void publishProgressThrottledPublishesAgainAfterThrottleWindowElapses() throws InterruptedException {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishProgress("phase", 1, 10, "item-1", false);
        Thread.sleep(120); // exceed PROGRESS_PUBLISH_INTERVAL_MS (100ms)
        job.callPublishProgress("phase", 2, 10, "item-2", false);

        verify(eventPublisher, times(2)).publishEvent(any(BackgroundProcessEvent.class));
    }

    @Test
    void publishInterruptedPublishesInterruptedEvent() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishInterrupted();

        ArgumentCaptor<BackgroundProcessEvent> captor = ArgumentCaptor.forClass(BackgroundProcessEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()
                         .getEventType()).isEqualTo(BackgroundProcessEventType.INTERRUPTED);
    }

    @Test
    void publishFailedPublishesFailedEventWithError() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;
        RuntimeException error = new RuntimeException("bad things");

        job.callPublishFailed(error);

        ArgumentCaptor<BackgroundProcessEvent> captor = ArgumentCaptor.forClass(BackgroundProcessEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()
                         .getEventType()).isEqualTo(BackgroundProcessEventType.FAILED);
    }

    @Test
    void publishEndedPublishesEndedEvent() {
        RecordingJob job = new RecordingJob(null);
        job.eventPublisher = eventPublisher;

        job.callPublishEnded("all done");

        ArgumentCaptor<BackgroundProcessEvent> captor = ArgumentCaptor.forClass(BackgroundProcessEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()
                         .getEventType()).isEqualTo(BackgroundProcessEventType.ENDED);
    }

    @Test
    void runDoesNotPropagateExceptionToCaller() {
        RecordingJob job = new RecordingJob(() -> {
            throw new IllegalStateException("should be swallowed");
        });

        // run() must catch and record failure rather than throwing
        job.run(eventPublisher);

        assertThat(job.getJobStatus()).isEqualTo(FAILED);
    }

    // ---------------------------------------------------------------
    // Test fixture
    // ---------------------------------------------------------------

    /**
     * Minimal concrete BackgroundJob whose runImpl() behavior is supplied per-test, and which
     * exposes the protected publish* helpers for direct testing.
     */
    static class RecordingJob extends BackgroundJob {
        private RunImplAction action;

        RecordingJob(RunImplAction action) {
            this.action = action;
        }

        void setAction(RunImplAction action) {
            this.action = action;
        }

        @Override
        public String getProcessName() {
            return "test-process";
        }

        @Override
        public void runImpl() throws Exception {
            if (action != null) {
                action.run();
            }
        }

        void callPublishStarted(String description) {
            publishStarted(description);
        }

        void callPublishInProgress(String description, int progress, int maxProgress) {
            publishInProgress(description, progress, maxProgress);
        }

        void callPublishProgress(String phaseText, int progress, int maxProgress, String currentItem, boolean isLastItem) {
            publishProgressThrottled(phaseText, progress, maxProgress, currentItem, isLastItem);
        }

        void callPublishInterrupted() {
            publishInterrupted();
        }

        void callPublishFailed(Exception e) {
            publishFailed(e);
        }

        void callPublishEnded(String description) {
            publishEnded(description);
        }
    }

    @FunctionalInterface
    interface RunImplAction {
        void run() throws Exception;
    }
}
