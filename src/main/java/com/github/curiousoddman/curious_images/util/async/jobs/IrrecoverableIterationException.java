package com.github.curiousoddman.curious_images.util.async.jobs;

public class IrrecoverableIterationException extends Exception {
    public IrrecoverableIterationException(Throwable cause) {
        super(cause);
    }

    public IrrecoverableIterationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
