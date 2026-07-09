package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.util.StartupRunnable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

// FIXME: Person albums - how to address wrong faces for person assignments.

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationMain implements ApplicationRunner {
    private final List<StartupRunnable> startupRunnableList;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running startup tasks...");
        for (StartupRunnable task : startupRunnableList) {
            try {
                task.onStartup();
            } catch (Exception e) {
                log.error("Error running startup task: {}", task.getClass()
                                                                .getSimpleName(), e);
            }
        }
        log.info("Startup tasks completed.");
    }
}
