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
        var queuedClasses = queue.stream()
                                 .map(ManagedJob::job)
                                 .map(Object::getClass)
                                 .toList();

        if (currentJob != null && sameClass(job, currentJob)
                || queuedClasses.contains(job.getClass())) {
            log.info("Discarded job submission - same class '{}' already in queue '{}'", job.getClass(), queuedClasses);
            return Optional.empty();
        }

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

    private static boolean sameClass(BackgroundJob job, ManagedJob managedJob) {
        return managedJob.job()
                         .getClass()
                         .equals(job.getClass());
    }

    public Optional<JobDescriptor> submitImportJob(List<String> paths) {
        return submit(jobFactory.createImportJob(paths));
    }

    public Optional<JobDescriptor> submitDuplicatesJob() {
        return submit(jobFactory.createDuplicateDetectionJob());
    }

    public Optional<JobDescriptor> submitAiPipelineJob() {
        return submit(jobFactory.createAiPipelineJob());
    }

    public Optional<JobDescriptor> submitAddFilesJob(AddFilesRequest request) {
        return submit(jobFactory.createAddFilesJob(request));
    }
}