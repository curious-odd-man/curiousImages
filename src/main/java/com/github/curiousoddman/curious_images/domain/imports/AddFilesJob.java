package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import com.github.curiousoddman.curious_images.util.CopyFileTreeVisitor;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class AddFilesJob extends BackgroundJob {

    public static final String ADD_FILES = "Add Files";

    private final ImportJob       importJob;
    private final JobManager      jobManager;
    private final AddFilesRequest request;

    // ── Core logic ────────────────────────────────────────────────────────────

    @Override
    public void runImpl() {
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
                importJob.runImportInternal(root);
            }

            publishEnded("Files added successfully");

            // ── Phase 3: optional post-processing ─────────────────────────────
            if (request.runAiPipeline()) {
                log.info("AddFilesService: triggering AI pipeline");
                jobManager.submitAiPipelineJob();
            }
            if (request.runDuplicateDetection()) {
                log.info("AddFilesService: triggering duplicate detection");
                jobManager.submitDuplicatesJob();
            }

        } catch (Exception e) {
            log.error("AddFilesService failed", e);
            publishFailed(e);
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
     * to {@link ImportJob}
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

    @Override
    public String getProcessName() {
        return ADD_FILES;
    }
}
