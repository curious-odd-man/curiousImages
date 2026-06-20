package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.imports.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.RescanLibraryEvent;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The photo import pipeline: recursively scans a chosen folder, extracts metadata, generates
 * thumbnails, and persists everything idempotently so re-running a scan is fast and safe.
 * <p>
 * This is a rewrite of the former {@code domain.tags.FilesScanningService} stub (now deleted),
 * which only walked the file tree and published progress events but never implemented
 * {@code extractMetadataAndUpdateDatabase}. While rewriting, two pre-existing bugs are fixed:
 * <ol>
 *     <li>The old code's {@code try/catch} wrapped the entire scan loop, so one corrupt/unreadable
 *     file aborted the whole import. Each file's processing is now isolated (see the
 *     {@code catch} inside the loop below).</li>
 *     <li>Nothing prevented a second {@link RescanLibraryEvent} from starting a second concurrent
 *     scan thread while one was already running. {@link #running} now guards against that.</li>
 * </ol>
 * See implementation plan §8 for the full algorithm this class implements.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportService {
    public static final String IMPORT_SCAN = "Import Scan";

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "cr2");
    private static final int PROGRESS_PUBLISH_INTERVAL_MS = 100;
    private static final int DB_FLUSH_BATCH_SIZE = 200;
    private static final int APPROXIMATE_LIBRARY_SIZE = 25_000;

    private final ApplicationEventPublisher eventPublisher;
    private final DSLContext dsl;
    private final ImportRootRepository importRootRepository;
    private final FolderRepository folderRepository;
    private final PhotoRepository photoRepository;
    private final ThumbnailRepository thumbnailRepository;
    private final PhotoMetadataExtractor metadataExtractor;
    private final ThumbnailGenerator thumbnailGenerator;
    private final TimeProvider timeProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean shouldInterrupt;
    private long lastProgressPublishMs;

    @EventListener
    public void onRescanEvent(RescanLibraryEvent event) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Import already running, ignoring new RescanLibraryEvent for {}", event.getPath());
            return;
        }
        shouldInterrupt = false;
        lastProgressPublishMs = 0;
        new Thread(() -> runImport(event.getPath()), "import-scan").start();
    }

    @EventListener
    public void onInterruptBackgroundProcess(InterruptBackgroundProcessEvent event) {
        shouldInterrupt = true;
    }

    private void runImport(String rootPathString) {
        log.info("Starting import scan of {}", rootPathString);
        publishStarted();
        int imported = 0;
        int skipped = 0;
        int errors = 0;
        try {
            Path rootPath = Path.of(rootPathString);
            long importRootId = importRootRepository.findOrCreate(rootPathString, timeProvider.now());
            // Caches folder ids per directory for this run only, so directories shared by many
            // files (the common case) are looked up/created once instead of once per file.
            Map<Path, Long> folderIdCache = new HashMap<>();

            List<Path> files = scan(rootPath);
            log.info("Discovered {} supported files under {}", files.size(), rootPathString);
            publishDiscovered(files.size());

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (shouldInterrupt) {
                    flush(buffer);
                    log.info("Import scan interrupted after {} files", i);
                    publishInterrupted();
                    return;
                }

                Path file = files.get(i);
                try {
                    ImportOutcome outcome = importOneFile(rootPath, importRootId, folderIdCache, file, buffer);
                    if (outcome == ImportOutcome.SKIPPED_UNCHANGED) {
                        skipped++;
                    } else {
                        imported++;
                    }
                } catch (Exception e) {
                    errors++;
                    log.error("Failed to import {}", file, e);
                }

                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                    flush(buffer);
                }
                maybePublishProgress(i, files.size(), file);
            }
            flush(buffer);

            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            log.info("Import scan of {} completed: {} imported/updated, {} skipped, {} errors",
                    rootPathString, imported, skipped, errors);
            publishEnded(imported, skipped, errors);
        } catch (Exception e) {
            log.error("Import scan of {} failed", rootPathString, e);
            publishFailed(e);
        } finally {
            running.set(false);
        }
    }

    /**
     * Imports (or rescans) a single file. Folder upsert, skip-vs-reprocess decision (§12),
     * metadata extraction, and thumbnail generation all happen here; the actual PHOTO/THUMBNAIL
     * writes are queued into {@code buffer} rather than executed immediately — see {@link #flush}
     * and implementation plan §13 for why.
     * <p>
     * The one exception is a brand-new photo's INSERT, which always executes immediately: it is
     * the only write in this method that needs a freshly generated id back (to attach a
     * THUMBNAIL row to), so it cannot be deferred into the batch the way every other write here
     * can.
     */
    private ImportOutcome importOneFile(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                        Path file, List<Query> buffer) throws IOException {
        long folderId = resolveFolderId(importRootId, rootPath, file.getParent(), folderIdCache);

        String absolutePath = file.toAbsolutePath().toString();
        String filename = file.getFileName().toString();
        String extension = extensionOf(filename);
        long fileSize = Files.size(file);
        LocalDateTime now = timeProvider.now();

        Optional<PhotoRecord> existing = photoRepository.findByAbsolutePath(absolutePath);

        if (existing.isPresent() && existing.get().getFileSize() == fileSize) {
            // Cheap rescan path: file unchanged since last scan. No metadata re-extraction, no
            // thumbnail regeneration
            buffer.add(photoRepository.touchLastSeenAtQuery(existing.get().getId(), now));
            return ImportOutcome.SKIPPED_UNCHANGED;
        }

        ExtractedMetadata metadata = metadataExtractor.extract(file, extension);

        if (existing.isPresent()) {
            long photoId = existing.get().getId();
            buffer.add(photoRepository.updateMetadataQuery(photoId, fileSize, metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(), now));
            queueThumbnail(photoId, file, extension, now, buffer);
            return ImportOutcome.UPDATED;
        }

        long photoId = photoRepository.insert(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(), metadata.captureDate(), metadata.captureDateSource(), now);
        queueThumbnail(photoId, file, extension, now, buffer);
        return ImportOutcome.IMPORTED;
    }

    private void queueThumbnail(long photoId, Path file, String extension, LocalDateTime now, List<Query> buffer) {
        thumbnailGenerator.generate(photoId, file, extension)
                .ifPresent(thumbnail -> buffer.add(thumbnailRepository.upsertQuery(
                        photoId, thumbnail.cachePath(), thumbnail.width(), thumbnail.height(), now)));
        // If generate() returned empty (no embedded preview, corrupt file, unsupported format),
        // we simply don't queue a THUMBNAIL row — the future Grid view falls back to
        // img/noimage.png for photos with no thumbnail row. Not treated as an error.
    }

    /**
     * Resolves (creating if necessary) the FOLDER row id for {@code dir}, walking up to the
     * import root first so the full ancestor chain exists with correct {@code parent_folder_id}
     * links — needed for the future Folder Tree view, even though this phase doesn't build one.
     * The import root itself is folder row {@code relative_path = ""} (see migration plan §4).
     */
    private long resolveFolderId(long importRootId, Path rootPath, Path dir, Map<Path, Long> folderIdCache) {
        Long cached = folderIdCache.get(dir);
        if (cached != null) {
            return cached;
        }

        long id;
        if (dir.equals(rootPath)) {
            String rootName = rootPath.getFileName() != null ? rootPath.getFileName().toString() : rootPath.toString();
            id = folderRepository.findOrCreate(importRootId, null, "", rootName);
        } else {
            long parentId = resolveFolderId(importRootId, rootPath, dir.getParent(), folderIdCache);
            String relativePath = rootPath.relativize(dir).toString();
            id = folderRepository.findOrCreate(importRootId, parentId, relativePath, dir.getFileName().toString());
        }
        folderIdCache.put(dir, id);
        return id;
    }

    /**
     * Flushes buffered PHOTO/THUMBNAIL writes via a single batched transaction, then clears the buffer.
     */
    private void flush(List<Query> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        dsl.transaction(configuration -> DSL.using(configuration).batch(buffer).execute());
        buffer.clear();
    }

    /**
     * Only publishes if {@value #PROGRESS_PUBLISH_INTERVAL_MS}ms have passed, or it's the last file — see §13.
     */
    private void maybePublishProgress(int index, int total, Path currentFile) {
        long nowMs = System.currentTimeMillis();
        boolean isLastFile = index == total - 1;
        if (!isLastFile && nowMs - lastProgressPublishMs < PROGRESS_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastProgressPublishMs = nowMs;
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.IN_PROGRESS)
                .progress(index + 1)
                .maxProgress(total)
                .description("Importing photos...")
                .currentItem(currentFile.toString())
                .build());
    }

    private List<Path> scan(Path rootPath) throws IOException {
        List<Path> found = new ArrayList<>(APPROXIMATE_LIBRARY_SIZE);
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && SUPPORTED_EXTENSIONS.contains(extensionOf(file.getFileName().toString()))) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void publishStarted() {
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.STARTED)
                .description("Discovering files...")
                .maxProgress(-1)
                .build());
    }

    private void publishDiscovered(int total) {
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.IN_PROGRESS)
                .maxProgress(total)
                .description("Importing photos...")
                .build());
    }

    private void publishInterrupted() {
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.INTERRUPTED)
                .description("Interrupted")
                .build());
    }

    private void publishFailed(Exception e) {
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.FAILED)
                .description("Import failed: " + e.getMessage())
                .error(e)
                .build());
    }

    private void publishEnded(int imported, int skipped, int errors) {
        eventPublisher.publishEvent(eventBuilder()
                .eventType(BackgroundProcessEventType.ENDED)
                .description("Imported/updated %d, skipped %d unchanged, %d errors".formatted(imported, skipped, errors))
                .build());
    }

    private BackgroundProcessEvent.BackgroundProcessEventBuilder eventBuilder() {
        return BackgroundProcessEvent.builder()
                .source(this)
                .processName(IMPORT_SCAN);
    }
}
