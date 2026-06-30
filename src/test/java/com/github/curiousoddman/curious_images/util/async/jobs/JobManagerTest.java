package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.domain.imports.ImportJob;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JobManager}.
 *
 * JobManager spins up a real background worker thread in its constructor, so these tests
 * use latches / short polling loops rather than mocking the threading away. Each test creates
 * its own JobManager instance and tears it down via close() to avoid leaking worker threads.
 */
@Slf4j
class JobManagerTest {

    private JobManager                manager;
    private ApplicationEventPublisher eventPublisher;
    private JobFactory                jobFactory;

    private JobManager newManager() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        jobFactory = mock(JobFactory.class);
        manager = new JobManager(eventPublisher, jobFactory);
        return manager;
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    // ---------------------------------------------------------------
    // submit()
    // ---------------------------------------------------------------

    @Test
    void submitReturnsDescriptorForNewJob() {
        newManager();
        BlockingJob job = new BlockingJob("job-a");

        Optional<JobDescriptor> descriptor = manager.submit(job);

        assertThat(descriptor).isPresent();
        assertThat(descriptor.get().getName()).isEqualTo("job-a");
        job.release(); // let the worker finish so it doesn't linger
    }

    @Test
    void submitRejectsJobOfSameClassAlreadyQueued() {
        newManager();
        // first job occupies "currentJob" slot (worker picks it up almost immediately)
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        BlockingJob queuedFirst = new BlockingJob("queued-1");
        BlockingJob queuedSecondSameClass = new BlockingJob("queued-2");

        Optional<JobDescriptor> firstQueued = manager.submit(queuedFirst);
        Optional<JobDescriptor> secondQueued = manager.submit(queuedSecondSameClass);

        assertThat(firstQueued).isEmpty();
        assertThat(secondQueued).isEmpty();

        running.release();
        queuedFirst.release();
    }

    @Test
    void submitRejectsJobWhenSameClassIsCurrentlyRunning() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        BlockingJob sameClass = new BlockingJob("another");
        Optional<JobDescriptor> result = manager.submit(sameClass);

        assertThat(result).isEmpty();

        running.release();
    }

    @Test
    void submitAllowsDifferentJobClassesConcurrently() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        OtherBlockingJob other = new OtherBlockingJob("other");
        Optional<JobDescriptor> result = manager.submit(other);

        assertThat(result).isPresent();

        running.release();
        other.release();
    }

    @Test
    void submitReturnsEmptyAfterShutdown() {
        newManager();
        manager.close();

        Optional<JobDescriptor> result = manager.submit(new BlockingJob("too-late"));

        assertThat(result).isEmpty();
    }

    @Test
    void submitImportJobDelegatesToJobFactoryAndSubmits() {
        newManager();
        BlockingJob importJob = new BlockingJob("import");
        List<String> paths = List.of("/a/b.png", "/a/c.png");
        when(jobFactory.createImportJob(paths)).thenReturn(importJob);

        Optional<JobDescriptor> result = manager.submitImportJob(paths);

        assertThat(result).isPresent();
        verify(jobFactory).createImportJob(paths);
        importJob.release();
    }

    // ---------------------------------------------------------------
    // getQueuedJobs() / currentJob()
    // ---------------------------------------------------------------

    @Test
    void getQueuedJobsReflectsJobsWaitingBehindCurrentJob() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        BlockingJob queued = new OtherBlockingJob("queued");
        manager.submit(queued);

        waitUntil(() -> manager.getQueuedJobs().size() == 1);
        assertThat(manager.getQueuedJobs()).hasSize(1);
        assertThat(manager.getQueuedJobs().get(0).getName()).isEqualTo("queued");

        running.release();
        queued.release();
    }

    @Test
    void currentJobEmptyWhenNothingRunning() {
        newManager();
        assertThat(manager.currentJob()).isEmpty();
    }

    @Test
    void currentJobPresentWhileJobIsRunning() {
        newManager();
        BlockingJob job = new BlockingJob("running");
        manager.submit(job);

        waitUntil(() -> manager.currentJob().isPresent());
        assertThat(manager.currentJob()).isPresent();
        assertThat(manager.currentJob().get().getName()).isEqualTo("running");

        job.release();
        waitUntil(() -> manager.currentJob().isEmpty());
    }

    // ---------------------------------------------------------------
    // cancel() / interruptCurrentJob() / cancelAll()
    // ---------------------------------------------------------------

    @Test
    void cancelInterruptsCurrentJobById() {
        newManager();
        BlockingJob job = new BlockingJob("running");
        manager.submit(job);
        UUID id = waitUntilCurrentJobPresent().getId();

        manager.cancel(id);

        waitUntil(job::wasInterruptRequested);
        assertThat(job.wasInterruptRequested()).isTrue();

        job.release();
    }

    @Test
    void cancelInterruptsQueuedJobById() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        OtherBlockingJob queued = new OtherBlockingJob("queued");
        JobDescriptor queuedDescriptor = manager.submit(queued).orElseThrow();

        manager.cancel(queuedDescriptor.getId());

        assertThat(queued.wasInterruptRequested()).isTrue();

        running.release();
        queued.release();
    }

    @Test
    void cancelWithUnknownIdIsNoOp() {
        newManager();
        BlockingJob job = new BlockingJob("running");
        manager.submit(job);
        waitUntilCurrentJobPresent();

        manager.cancel(UUID.randomUUID());

        assertThat(job.wasInterruptRequested()).isFalse();
        job.release();
    }

    @Test
    void interruptCurrentJobDelegatesToCancelForRunningJob() {
        newManager();
        BlockingJob job = new BlockingJob("running");
        manager.submit(job);
        waitUntilCurrentJobPresent();

        manager.interruptCurrentJob();

        waitUntil(job::wasInterruptRequested);
        assertThat(job.wasInterruptRequested()).isTrue();
        job.release();
    }

    @Test
    void interruptCurrentJobIsNoOpWhenNothingRunning() {
        newManager();
        // should not throw even though currentJob is null
        manager.interruptCurrentJob();
    }

    @Test
    void cancelAllInterruptsQueuedAndCurrentJobs() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        OtherBlockingJob queued = new OtherBlockingJob("queued");
        manager.submit(queued);

        manager.cancelAll();

        assertThat(running.wasInterruptRequested()).isTrue();
        assertThat(queued.wasInterruptRequested()).isTrue();
        assertThat(manager.getQueuedJobs()).isEmpty();

        running.release();
        queued.release();
    }

    // ---------------------------------------------------------------
    // worker loop behavior
    // ---------------------------------------------------------------

    @Test
    void workerRunsSubmittedJobToCompletion() {
        newManager();
        AtomicInteger executions = new AtomicInteger();
        BlockingJob job = new BlockingJob("running", executions);
        manager.submit(job);

        job.release();

        waitUntil(() -> executions.get() == 1);
        assertThat(executions.get()).isEqualTo(1);
        waitUntil(() -> manager.currentJob().isEmpty());
    }

    @Test
    void workerSkipsQueuedJobsThatWereCancelledBeforeRunning() {
        newManager();
        BlockingJob running = new BlockingJob("running");
        manager.submit(running);
        waitUntilCurrentJobPresent();

        AtomicInteger queuedExecutions = new AtomicInteger();
        OtherBlockingJob queued = new OtherBlockingJob("queued", queuedExecutions);
        JobDescriptor queuedDescriptor = manager.submit(queued).orElseThrow();

        // cancel it while still queued, before the worker ever picks it up
        manager.cancel(queuedDescriptor.getId());

        running.release();
        waitUntil(() -> manager.currentJob().isEmpty());

        // worker loop should skip the cancelled job entirely (it dequeues and discards jobs
        // that are already interrupt-requested rather than running them); give it a moment to
        // process the queue fully and confirm it never actually ran
        sleep(200);
        assertThat(queuedExecutions.get()).isEqualTo(0);
    }

    @Test
    void closeStopsWorkerAndRejectsFurtherSubmissions() {
        newManager();
        manager.close();

        assertThat(manager.submit(new BlockingJob("after-close"))).isEmpty();
    }

    @Test
    void closeInterruptsCurrentlyRunningJob() {
        newManager();
        BlockingJob job = new BlockingJob("running");
        manager.submit(job);
        waitUntilCurrentJobPresent();

        manager.close();

        waitUntil(() -> manager.currentJob().isEmpty());
        assertThat(job.wasInterruptRequested()).isTrue();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private JobDescriptor waitUntilCurrentJobPresent() {
        waitUntil(() -> manager.currentJob().isPresent());
        return manager.currentJob().orElseThrow();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        throw new AssertionError("Condition not met within timeout");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------
    // Test job fixtures
    // ---------------------------------------------------------------

    /**
     * A job whose runImpl() blocks on a latch until the test explicitly releases it, polling
     * isInterruptRequested() like a real job's run loop would. Lets tests deterministically
     * control when a job is "in flight" vs "finished".
     */
    static class BlockingJob extends ImportJob {
        private final String          name;
        private final CountDownLatch  releaseLatch = new CountDownLatch(1);
        private final AtomicBoolean   interruptSeen = new AtomicBoolean(false);
        private final AtomicInteger   executions;

        BlockingJob(String name) {
            this(name, new AtomicInteger());
        }

        BlockingJob(String name, AtomicInteger executions) {
            super(null, null, null, null, null, null, null, null, null);
            this.name = name;
            this.executions = executions;
        }

        void release() {
            releaseLatch.countDown();
        }

        boolean wasInterruptRequested() {
            return interruptSeen.get() || isInterruptRequested();
        }

        @Override
        public String getProcessName() {
            return name;
        }

        @Override
        public void runImpl() throws Exception {
            while (!releaseLatch.await(10, TimeUnit.MILLISECONDS)) {
                if (isInterruptRequested()) {
                    interruptSeen.set(true);
                    return;
                }
            }
            executions.incrementAndGet();
        }
    }

    /** Distinct class from BlockingJob so manager's same-class dedup logic doesn't collide. */
    static class OtherBlockingJob extends BlockingJob {
        private final String         name;
        private final CountDownLatch releaseLatch = new CountDownLatch(1);
        private final AtomicBoolean  interruptSeen = new AtomicBoolean(false);
        private final AtomicInteger  executions;

        OtherBlockingJob(String name) {
            this(name, new AtomicInteger());
        }

        OtherBlockingJob(String name, AtomicInteger executions) {
            super(name, executions);
            this.name = name;
            this.executions = executions;
        }

        void release() {
            releaseLatch.countDown();
        }

        boolean wasInterruptRequested() {
            return interruptSeen.get() || isInterruptRequested();
        }

        @Override
        public String getProcessName() {
            return name;
        }

        @Override
        public void runImpl() throws Exception {
            while (!releaseLatch.await(10, TimeUnit.MILLISECONDS)) {
                if (isInterruptRequested()) {
                    interruptSeen.set(true);
                    return;
                }
            }
            executions.incrementAndGet();
        }
    }
}
