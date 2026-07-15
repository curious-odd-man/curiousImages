package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class JobManager {
    private final LinkedBlockingQueue<ManagedJob> queue = new LinkedBlockingQueue<>();
    private final ApplicationEventPublisher       eventPublisher;
    private final Thread                          worker;
    private final JobFactory                      jobFactory;

    private volatile ManagedJob currentJob;
    private volatile boolean    shutdown;


    public JobManager(ApplicationEventPublisher eventPublisher, JobFactory jobFactory) {
        this.eventPublisher = eventPublisher;
        this.jobFactory = jobFactory;
        worker = Thread.ofPlatform()
                       .name("BackgroundJob")
                       .start(this::workerLoop);
    }

    synchronized Optional<JobDescriptor> submit(BackgroundJob job) {
        if (shutdown) {
            return Optional.empty();
        }
        if (job.isSupersedable()) {
            return submitSupersedable(job);
        }

        var queuedClasses = queue.stream()
                                 .map(ManagedJob::job)
                                 .map(Object::getClass)
                                 .toList();

        if (currentJob != null && sameClass(job, currentJob.job())
                || queuedClasses.contains(job.getClass())) {
            log.info("Discarded job submission - same class '{}' already in queue '{}'", job.getClass(), queuedClasses);
            return Optional.empty();
        }

        return enqueue(job);
    }

    /**
     * Supersedable jobs (e.g. {@code ThumbnailGenerationJob}) always get accepted: any
     * not-yet-started queued instance of the same class is dropped — a newer request already
     * supersedes it — and a currently-running instance of the same class is asked to stop via
     * {@link BackgroundJob#requestInterrupt()}. It can't be aborted mid-file, so there's a small,
     * bounded, accepted delay while the previous run finishes its current in-flight file before
     * the worker picks up this newly-enqueued job. See implementation plan §5.
     */
    private Optional<JobDescriptor> submitSupersedable(BackgroundJob job) {
        queue.removeIf(managed -> sameClass(job, managed.job()));
        if (currentJob != null && sameClass(job, currentJob.job())) {
            currentJob.job()
                      .requestInterrupt();
        }
        return enqueue(job);
    }

    private Optional<JobDescriptor> enqueue(BackgroundJob job) {
        JobDescriptor descriptor = new JobDescriptor(UUID.randomUUID(), job.getProcessName(), "");
        ManagedJob    managed    = new ManagedJob(job, descriptor);

        queue.add(managed);
        log.info("Added job to queue. Current length = {}", queue.size());
        return Optional.of(descriptor);
    }

    public List<JobDescriptor> getQueuedJobs() {
        return queue.stream()
                    .map(ManagedJob::descriptor)
                    .toList();
    }

    public Optional<JobDescriptor> currentJob() {
        return Optional.ofNullable(currentJob)
                       .map(ManagedJob::descriptor);
    }

    public void interruptCurrentJob() {
        log.info("Requested interruption of current job: {}", currentJob);
        if (currentJob != null) {
            cancel(currentJob.descriptor()
                             .getId());
        }
    }

    public void cancel(UUID id) {
        log.info("Requested interrupt/cancel of job {}", id);
        if (currentJob != null) {
            if (currentJob.descriptor()
                          .getId()
                          .equals(id)) {
                currentJob.job()
                          .requestInterrupt();
                log.info("Interupting current job...");
                return;
            }
        }

        for (ManagedJob managedJob : queue) {
            if (managedJob.descriptor()
                          .getId()
                          .equals(id)) {
                managedJob.job()
                          .requestInterrupt();
                log.info("Cancelling queued job");
                return;
            }
        }
    }

    public void cancelAll() {
        log.info("Cancel all requested...");
        ManagedJob queued;

        while ((queued = queue.poll()) != null) {
            queued.job()
                  .requestInterrupt();
        }

        if (currentJob != null) {
            currentJob.job()
                      .requestInterrupt();
        }
    }

    private void workerLoop() {
        while (!shutdown) {
            ManagedJob managed;

            try {
                do {
                    log.info("Waiting for next non-interrupted job");
                    managed = queue.take();
                    log.info("Got job '{}' in status '{}'",
                            managed.descriptor()
                                   .getName(),
                            managed.job()
                                   .getJobStatus());
                } while (managed.job()
                                .isInterruptRequested());
            } catch (InterruptedException e) {
                if (shutdown) {
                    break;
                }

                continue;
            }

            synchronized (this) {
                currentJob = managed;
            }
            log.info("Ready to run next job...");

            try {
                managed.job()
                       .run(eventPublisher);
            } catch (Throwable t) {
                log.error("Background Job failed {}", t.getMessage(), t);
            } finally {
                synchronized (this) {
                    currentJob = null;
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        log.info("Shutting down...");

        cancelAll();

        while (!queue.isEmpty() || currentJob != null) {
            Thread.yield();
        }

        shutdown = true;
        worker.interrupt();

        try {
            worker.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private static boolean sameClass(BackgroundJob job, BackgroundJob other) {
        return other.getClass()
                    .equals(job.getClass());
    }

    public Optional<JobDescriptor> submitImportJob(List<String> paths) {
        return submit(jobFactory.createImportJob(paths));
    }

    public Optional<JobDescriptor> submitDuplicatesJob() {
        return submit(jobFactory.createDuplicateDetectionJob());
    }

    public Optional<JobDescriptor> submitAiPipelineJob() {
        return submit(jobFactory.createAiPipelineJob(this));
    }

    public Optional<JobDescriptor> submitAddFilesJob(AddFilesRequest request) {
        return submit(jobFactory.createAddFilesJob(request, this));
    }

    /**
     * Requests on-demand real-thumbnail generation for a page/selection of photo IDs — submitted
     * by {@code LibraryController} whenever the grid is about to render a set of photo IDs.
     * Supersedable (see {@code ThumbnailGenerationJob#isSupersedable()}) — a fresh call always
     * takes priority over one still queued or running for a previous selection.
     */
    public Optional<JobDescriptor> submitThumbnailGenerationJob(List<Long> photoIds) {
        return submit(jobFactory.createThumbnailGenerationJob(photoIds));
    }

    public Optional<JobDescriptor> submitAlbumGenerationJob() {
        return submit(jobFactory.createAlbumGenerationJob());
    }

    /**
     * Submits a download of whatever AI models are currently missing (see
     * {@code ModelPaths#missingModels()}). Like other non-supersedable jobs, a second submission
     * while one is already queued/running is silently discarded — the in-flight download will
     * still complete and publish its {@code ENDED} event either way.
     *
     * @param onSuccess run once the download finishes successfully; pass {@code () -> {}} for a
     *                  plain background download, or {@code this::submitAiPipelineJob} to chain
     *                  into the AI pipeline afterward.
     */
    public Optional<JobDescriptor> submitModelDownloadJob(Runnable onSuccess) {
        return submit(jobFactory.createModelDownloadJob(onSuccess));
    }
}
