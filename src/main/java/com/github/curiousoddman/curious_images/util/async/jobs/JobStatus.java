package com.github.curiousoddman.curious_images.util.async.jobs;

public enum JobStatus {
    NEVER_RUN,
    RUNNING,
    COMPLETED,
    INTERRUPT_REQUESTED,
    INTERRUPTED,
    FAILED
}