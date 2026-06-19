package com.github.curiousoddman.curious_images.domain.tags;

import com.github.curiousoddman.curious_images.domain.DataAccess;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.RescanLibraryEvent;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FilesScanningService /*implements StartupRunnable*/ {
    private static final int APPROXIMATE_SIZE_OF_MY_LIBRARY = 5000;
    public static final String LIBRARY_SCAN = "Library Scan";
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DataAccess dataAccess;

    private boolean shouldInterrupt;

    @EventListener
    public void onRescanEvent(RescanLibraryEvent event) {
        String libraryRoot = event.getPath();
        shouldInterrupt = false;
        log.info("Received rescan library event...");
        Runnable rescanRunnable = () -> {
            log.info("Starting scanning: discover files...");
            publishStartedEvent();
            try {
                List<Path> paths = doScan(Path.of(libraryRoot));
                log.info("Discovered {} files. Started processing", paths.size());
                publishInProgressEvent(paths);
                for (int i = 0; i < paths.size(); i++) {
                    Path file = paths.get(i);
                    MDC.put("file", String.valueOf(i));
                    log.info("\t{}", file);
                    if (shouldInterrupt) {
                        log.info("Scanning interrupted");
                        publishInterruptedEvent();
                        return;
                    }

                    extractMetadataAndUpdateDatabase(file);
                    publishInProgressStatusEvent(i, paths);
                }
                publishCompletedEvent();
                log.info("Scanning completed...");
            } catch (Exception e) {
                publishFailedEvent(e);
                log.error("Failed parsing files...", e);
                MDC.remove("file");
            }
        };
        Thread rescanThread = new Thread(rescanRunnable, "rescan");
        rescanThread.start();
    }

    private void extractMetadataAndUpdateDatabase(Path file) {
       // FIXME
    }

    @EventListener
    public void onInterruptBackgroundProcess(InterruptBackgroundProcessEvent event) {
        shouldInterrupt = true;
    }

    @SneakyThrows
    private List<Path> doScan(Path path) {
        List<Path> foundFiles = new ArrayList<>(APPROXIMATE_SIZE_OF_MY_LIBRARY);
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    foundFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return foundFiles;
    }

    private void publishFailedEvent(Exception e) {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.FAILED)
                        .description("Failed...")
                        .error(e)
                        .build());
    }

    private void publishCompletedEvent() {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.ENDED)
                        .description("Interrupted")
                        .build());
    }

    private void publishInProgressStatusEvent(int i, List<Path> paths) {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.IN_PROGRESS)
                        .progress(i + 1)
                        .maxProgress(paths.size())
                        .description("Fetching metadata...")
                        .build());
    }

    private void publishInterruptedEvent() {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.INTERRUPTED)
                        .description("Interrupted")
                        .build());
    }

    private void publishInProgressEvent(List<Path> paths) {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.IN_PROGRESS)
                        .maxProgress(paths.size())
                        .description("Fetching metadata...")
                        .build());
    }

    private void publishStartedEvent() {
        applicationEventPublisher.publishEvent(
                getBackgroundProcessEventBuilder()
                        .eventType(BackgroundProcessEventType.STARTED)
                        .description("Discovering files...")
                        .maxProgress(-1)
                        .build());
    }

    private BackgroundProcessEvent.BackgroundProcessEventBuilder getBackgroundProcessEventBuilder() {
        return BackgroundProcessEvent
                .builder()
                .source(this)
                .processName(LIBRARY_SCAN);
    }
}
