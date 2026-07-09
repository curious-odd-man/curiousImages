package com.github.curiousoddman.curious_images.util.async;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ThreadUtils {
    public static void runOnDaemonThread(String name, Runnable runnable) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        t.start();
    }
}
