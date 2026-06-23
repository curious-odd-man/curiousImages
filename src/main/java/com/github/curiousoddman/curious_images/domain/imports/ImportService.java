package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.event.RescanLibraryEvent;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.AbstractBackgroundJob;
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

/**
 * The photo import pipeline: recursively scans a chosen folder, extracts metadata, generates
 * thumbnails, and persists everything idempotently so re-running a scan is fast and safe.
 * <p>
 * Run-state (single-flight guard, interrupt flag) and {@code BackgroundProcessEvent} publishing
 * now live in {@link AbstractBackgroundJob} — this class only implements what's specific to
 * importing: the file walk, metadata/thumbnail pipeline, and batched persistence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportService extends AbstractBackgroundJob {
    public static final String IMPORT_SCAN = "Import Scan";

    private static final Set<String> SUPPORTED_EXTENSIONS     = Set.of("jpg", "jpeg", "png", "cr2");
    private static final int         DB_FLUSH_BATCH_SIZE      = 200;
    private static final int         APPROXIMATE_LIBRARY_SIZE = 25_000;

    private final DSLContext                dsl;
    private final ImportRootRepository      importRootRepository;
    private final FolderRepository          folderRepository;
    private final PhotoRepository           photoRepository;
    private final ThumbnailRepository       thumbnailRepository;
    private final PhotoMetadataExtractor    metadataExtractor;
    private final ThumbnailGenerator        thumbnailGenerator;
    private final TimeProvider              timeProvider;
    private final ApplicationEventPublisher applicationEventPublisher;

    @EventListener
    public void onRescanEvent(RescanLibraryEvent event) {
        if (!tryStart()) {
            log.warn("Import already running, ignoring new RescanLibraryEvent for {}", event.getPath());
            return;
        }
        new Thread(() -> runImport(event.getPath()), "import-scan").start();
    }

    @EventListener
    public void onInterruptBackgroundProcess(InterruptBackgroundProcessEvent event) {
        requestInterrupt();
    }

    private void runImport(String rootPathString) {
        log.info("Starting import scan of {}", rootPathString);
        publishStarted("Discovering files...");
        int imported = 0;
        int skipped  = 0;
        int errors   = 0;
        try {
            Path rootPath     = Path.of(rootPathString);
            long importRootId = importRootRepository.findOrCreate(rootPathString, timeProvider.now());
            // Caches folder ids per directory for this run only, so directories shared by many
            // files (the common case) are looked up/created once instead of once per file.
            Map<Path, Long> folderIdCache = new HashMap<>();

            List<Path> files = scan(rootPath);
            log.info("Discovered {} supported files under {}", files.size(), rootPathString);
            publishInProgress("Importing photos...", 0, files.size());

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (isInterruptRequested()) {
                    flush(buffer);
                    log.info("Import scan interrupted after {} files", i);
                    publishInterrupted("Interrupted");
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
                publishProgress("Importing photos...", i + 1, files.size(), file.toString(), i == files.size() - 1);
            }
            flush(buffer);

            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            log.info("Import scan of {} completed: {} imported/updated, {} skipped, {} errors",
                    rootPathString, imported, skipped, errors);
            publishEnded("Imported/updated %d, skipped %d unchanged, %d errors".formatted(imported, skipped, errors));
            applicationEventPublisher.publishEvent(new LibraryUpdatedEvent(this));
        } catch (Exception e) {
            log.error("Import scan of {} failed", rootPathString, e);
            publishFailed(e);
        } finally {
            finish();
        }
    }

    /**
     * Imports (or rescans) a single file. Folder upsert, skip-vs-reprocess decision, metadata
     * extraction, and thumbnail generation all happen here; the actual PHOTO/THUMBNAIL writes are
     * queued into {@code buffer} rather than executed immediately — see {@link #flush}.
     * <p>
     * The one exception is a brand-new photo's INSERT, which always executes immediately: it is
     * the only write in this method that needs a freshly generated id back (to attach a
     * THUMBNAIL row to), so it cannot be deferred into the batch the way every other write here
     * can.
     */
    private ImportOutcome importOneFile(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                        Path file, List<Query> buffer) throws IOException {
        long folderId = resolveFolderId(importRootId, rootPath, file.getParent(), folderIdCache);

        String absolutePath = file.toAbsolutePath()
                                  .toString();
        String filename = file.getFileName()
                              .toString();
        String        extension = extensionOf(filename);
        long          fileSize  = Files.size(file);
        LocalDateTime now       = timeProvider.now();

        Optional<PhotoRecord> existing = photoRepository.findByAbsolutePath(absolutePath);

        if (existing.isPresent() && existing.get()
                                            .getFileSize() == fileSize) {
            // Cheap rescan path: file unchanged since last scan. No metadata re-extraction, no
            // thumbnail regeneration
            buffer.add(photoRepository.touchLastSeenAtQuery(existing.get()
                                                                    .getId(), now));
            return ImportOutcome.SKIPPED_UNCHANGED;
        }

        ExtractedMetadata metadata = metadataExtractor.extract(file, extension);

        if (existing.isPresent()) {
            long photoId = existing.get()
                                   .getId();
            buffer.add(photoRepository.updateMetadataQuery(photoId, fileSize, metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.orientationDegrees(), metadata.cameraMake(), metadata.cameraModel(), metadata.lensModel(),
                    metadata.exifExtraJson(), now));
            queueThumbnail(photoId, file, extension, metadata.orientationDegrees(), now, buffer);
            return ImportOutcome.UPDATED;
        }

        long photoId = photoRepository.insert(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(), metadata.captureDate(), metadata.captureDateSource(),
                metadata.orientationDegrees(), metadata.cameraMake(), metadata.cameraModel(), metadata.lensModel(),
                metadata.exifExtraJson(), now);
        queueThumbnail(photoId, file, extension, metadata.orientationDegrees(), now, buffer);
        return ImportOutcome.IMPORTED;
    }

    private void queueThumbnail(long photoId, Path file, String extension, int rotationDegrees,
                                LocalDateTime now, List<Query> buffer) {
        thumbnailGenerator.generate(photoId, file, extension, rotationDegrees)
                          .ifPresent(thumbnail -> buffer.add(thumbnailRepository.upsertQuery(
                                  photoId, thumbnail.cachePath(), thumbnail.width(), thumbnail.height(), now)));
        // If generate() returned empty (no embedded preview, corrupt file, unsupported format),
        // we simply don't queue a THUMBNAIL row — the Grid view falls back to img/noimage.png for
        // photos with no thumbnail row. Not treated as an error.
    }

    /**
     * Resolves (creating if necessary) the FOLDER row id for {@code dir}, walking up to the
     * import root first so the full ancestor chain exists with correct {@code parent_folder_id}
     * links — needed for the Folder Tree view. The import root itself is folder row
     * {@code relative_path = ""}.
     */
    private long resolveFolderId(long importRootId, Path rootPath, Path dir, Map<Path, Long> folderIdCache) {
        Long cached = folderIdCache.get(dir);
        if (cached != null) {
            return cached;
        }

        long id;
        if (dir.equals(rootPath)) {
            String rootName = rootPath.getFileName() != null ? rootPath.getFileName()
                                                                       .toString() : rootPath.toString();
            id = folderRepository.findOrCreate(importRootId, null, "", rootName);
        } else {
            long parentId = resolveFolderId(importRootId, rootPath, dir.getParent(), folderIdCache);
            String relativePath = rootPath.relativize(dir)
                                          .toString();
            id = folderRepository.findOrCreate(importRootId, parentId, relativePath, dir.getFileName()
                                                                                        .toString());
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
        dsl.transaction(configuration -> DSL.using(configuration)
                                            .batch(buffer)
                                            .execute());
        buffer.clear();
    }

    private List<Path> scan(Path rootPath) throws IOException {
        List<Path> found = new ArrayList<>(APPROXIMATE_LIBRARY_SIZE);
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && SUPPORTED_EXTENSIONS.contains(extensionOf(file.getFileName()
                                                                                           .toString()))) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1)
                                      .toLowerCase(Locale.ROOT);
    }

    @Override
    protected ApplicationEventPublisher eventPublisher() {
        return applicationEventPublisher;
    }

    @Override
    protected String getProcessName() {
        return IMPORT_SCAN;
    }
}
