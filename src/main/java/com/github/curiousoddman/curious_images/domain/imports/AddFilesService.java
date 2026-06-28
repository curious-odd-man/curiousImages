package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionService;
import com.github.curiousoddman.curious_images.event.RunAiPipelineEvent;
import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import com.github.curiousoddman.curious_images.util.CopyFileTreeVisitor;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.async.AbstractBackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Background job that handles the "Add files / folders" workflow:
 * <ol>
 *   <li>Optionally copies source trees into a destination folder, preserving
 *       subfolder structure (skips files that already exist at the destination
 *       with the same size — cheap idempotency, same philosophy as
 *       {@link ImportService}).</li>
 *   <li>Resolves the effective scan roots (destination when copying, original
 *       source paths when registering in-place as new roots) and hands each one
 *       to {@link ImportService#runImportInternal(String)} sequentially.</li>
 *   <li>Optionally fires {@link RunAiPipelineEvent} and/or starts
 *       {@link DuplicateDetectionService} after the scan completes.</li>
 * </ol>
 *
 * <h3>Concurrency / single-flight</h3>
 * {@link AbstractBackgroundJob#tryStart()} is checked at entry. If
 * {@link ImportService} is already running its own scan,
 * {@link ImportService#tryStart()} will return {@code false} when called from
 * here. However, this service and {@code ImportService} share no guard; the
 * caller ({@link com.github.curiousoddman.curious_images.ui.controller.screen.AddFilesController})
 * calls {@link #start(AddFilesRequest)} and shows a blocking alert when
 * {@code false} is returned, per the spec. Both services extend
 * {@link AbstractBackgroundJob} independently, so the UI is responsible for
 * not starting a second job while the first is running (the controllers check
 * {@link #isRunning()} / {@link ImportService#isRunning()} before opening the
 * dialogs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddFilesService extends AbstractBackgroundJob {

    public static final String ADD_FILES = "Add Files";

    private final ImportService             importService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final ApplicationEventPublisher applicationEventPublisher;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the add-files job on a dedicated background thread.
     *
     * @return {@code true}  if the job was accepted and started;
     * {@code false} if this service OR {@link ImportService} is already
     * running (caller must show a blocking error dialog).
     */
    public boolean start(AddFilesRequest request) {
        if (importService.isRunning()) {
            log.warn("AddFilesService: ImportService is already running — refusing to start");
            return false;
        }
        if (!tryStart()) {
            log.warn("AddFilesService: already running — refusing to start");
            return false;
        }
        Thread t = new Thread(() -> run(request), "add-files");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    private void run(AddFilesRequest request) {
        publishStarted("Preparing to add files…");
        try {
            List<String> scanRoots;

            if (request.copyToDestination()) {
                // ── Phase 1: copy source trees into the destination ────────────
                scanRoots = copySourcesIntoDestination(request);
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }
            } else {
                // ── In-place: scan source paths directly as new roots ──────────
                scanRoots = request.sourcePaths();
            }

            // ── Phase 2: import every effective root via ImportService ─────────
            publishInProgress("Scanning imported files…", 0, scanRoots.size());
            for (int i = 0; i < scanRoots.size(); i++) {
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }
                String root = scanRoots.get(i);
                log.info("AddFilesService: handing off root {} of {} to ImportService: {}",
                        i + 1, scanRoots.size(), root);
                publishInProgress("Importing root " + (i + 1) + " of " + scanRoots.size()
                        + ": " + root, i, scanRoots.size());
                /*
                 * ImportService.runImportInternal() is the same method called by
                 * onRescanEvent. We call it directly (package-accessible) so we can
                 * sequence multiple roots in a single background job without going
                 * through the event bus (which would start a competing background job).
                 */
                importService.runImportInternal(root);
            }

            publishEnded("Files added successfully");

            // ── Phase 3: optional post-processing ─────────────────────────────
            // FIXME: These 2 will fire progress events -> ui will be broken. Should I use single threaded executor to effectively has only 1 background task running all the time?
            if (request.runAiPipeline()) {
                log.info("AddFilesService: triggering AI pipeline");
                applicationEventPublisher.publishEvent(new RunAiPipelineEvent(this));
            }
            if (request.runDuplicateDetection()) {
                log.info("AddFilesService: triggering duplicate detection");
                duplicateDetectionService.start();
            }

        } catch (Exception e) {
            log.error("AddFilesService failed", e);
            publishFailed(e);
        } finally {
            finish();
        }
    }

    /**
     * Copies every source path into {@code request.destinationFolder()},
     * preserving the relative subfolder structure of each source.
     * <p>
     * For each source path {@code S}, the copy root inside the destination is
     * {@code <dest>/<S.fileName>}. Files that already exist at the destination
     * with the exact same byte-size are skipped (cheap idempotency — no checksum).
     *
     * @return list of effective scan roots inside the destination to be handed
     * to {@link ImportService}
     */
    private List<String> copySourcesIntoDestination(AddFilesRequest request) throws IOException {
        Path         destRoot  = Path.of(request.destinationFolder());
        List<String> scanRoots = new java.util.ArrayList<>();

        List<String> sourcePaths = request.sourcePaths();
        for (int s = 0; s < sourcePaths.size(); s++) {
            if (isInterruptRequested()) {
                break;
            }
            Path src = Path.of(sourcePaths.get(s));
            String srcName = src.getFileName() != null ? src.getFileName()
                                                            .toString() : src.toString();
            Path copyRoot = destRoot.resolve(srcName);

            log.info("AddFilesService: copying {} → {}", src, copyRoot);
            publishInProgress("Copying source " + (s + 1) + " of " + sourcePaths.size() + ": " + srcName, s, sourcePaths.size());

            if (Files.isRegularFile(src)) {
                // Single-file drop: copy the file itself, scan its parent folder
                FileUtils.copyFileIfDifferentSize(src, copyRoot);
                scanRoots.add(destRoot.toString());
            } else {
                // Directory drop: walk the tree and mirror structure
                Files.walkFileTree(src, new CopyFileTreeVisitor(this::isInterruptRequested, src, copyRoot));
                scanRoots.add(copyRoot.toString());
            }
        }
        return scanRoots;
    }

    // ── AbstractBackgroundJob ─────────────────────────────────────────────────

    @Override
    protected ApplicationEventPublisher eventPublisher() {
        return applicationEventPublisher;
    }

    @Override
    protected String getProcessName() {
        return ADD_FILES;
    }
}
