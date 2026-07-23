package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.event.model.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.util.FileCollectingVisitor;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class ImportJob extends BackgroundJob {

    public static final String IMPORT_SCAN = "Import Scan";

    private static final Set<String> SUPPORTED_EXTENSIONS     = Set.of("jpg", "jpeg", "png", "cr2");
    private static final int         DB_FLUSH_BATCH_SIZE      = 200;
    private static final int         APPROXIMATE_LIBRARY_SIZE = 25_000;

    private final DSLContext             dsl;
    private final ImportRootRepository   importRootRepository;
    private final FolderRepository       folderRepository;
    private final MediaRepository        mediaRepository;
    private final PhotoPreviewRepository photoPreviewRepository;
    private final PhotoMetadataExtractor metadataExtractor;
    private final TimeProvider           timeProvider;
    private final List<String>           rootPaths;

    @Override
    public void runImpl() throws Exception {
        for (int i = 0; i < rootPaths.size(); i++) {
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }
            log.info("Multi-root scan: root {} of {}: {}", i + 1, rootPaths.size(), rootPaths.get(i));
            runImportInternal(rootPaths.get(i));
        }
        eventPublisher.publishEvent(new LibraryUpdatedEvent(this));
    }

    // ── Internal scan (synchronous, called on the background thread) ──────────

    void runImportInternal(String rootPathString) {
        log.info("Starting import scan of {}", rootPathString);
        publishStarted("Discovering files…");
        int imported = 0;
        int skipped  = 0;
        int errors   = 0;
        try {
            Path            rootPath      = Path.of(rootPathString);
            long            importRootId  = importRootRepository.findOrCreate(rootPathString, timeProvider.now());
            Map<Path, Long> folderIdCache = new HashMap<>();

            List<Path> files = scan(rootPath);
            log.info("Discovered {} supported files under {}", files.size(), rootPathString);
            publishInProgress("Reading files", 0, files.size());

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (isInterruptRequested()) {
                    flush(buffer);
                    log.info("Import scan interrupted after {} files", i);
                    return; // caller handles publishInterrupted
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
                publishProgressThrottled("Reading files", i + 1, files.size(),
                        file.toString(), i == files.size() - 1);
            }
            flush(buffer);

            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            log.info("Import scan of {} completed: {} imported/updated, {} skipped, {} errors",
                    rootPathString, imported, skipped, errors);
            publishEnded("Imported/updated %d, skipped %d unchanged, %d errors"
                    .formatted(imported, skipped, errors));
        } catch (Exception e) {
            log.error("Import scan of {} failed", rootPathString, e);
            publishFailed(e);
        }
    }

    // ── File-level import (metadata only — no thumbnail generation) ───────────

    private ImportOutcome importOneFile(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                        Path file, List<Query> buffer) throws IOException {
        long folderId = resolveFolderId(importRootId, rootPath, file.getParent(), folderIdCache);
        String absolutePath = file.toAbsolutePath()
                                  .toString();
        String filename = file.getFileName()
                              .toString();
        String        extension = FileUtils.extensionOf(filename);
        long          fileSize  = Files.size(file);
        LocalDateTime now       = timeProvider.now();

        Optional<MediaPhotoRecord> existing = mediaRepository.findByAbsolutePath(absolutePath);

        if (existing.isPresent() && existing.get()
                                            .getFileSize() == fileSize) {
            // Cheap rescan: file unchanged. Touch last_seen_at; do NOT reset AI status flags —
            // partial AI progress from a previous run is preserved.
            buffer.add(mediaRepository.touchLastSeenAtQuery(existing.get()
                                                                    .getId(), now));
            return ImportOutcome.SKIPPED_UNCHANGED;
        }

        ExtractedMetadata metadata = metadataExtractor.extract(file, extension);

        if (existing.isPresent()) {
            long photoId = existing.get()
                                   .getId();
            buffer.addAll(mediaRepository.updateMetadataQuery(photoId, fileSize,
                    metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.orientationDegrees(), metadata.cameraMake(),
                    metadata.cameraModel(), metadata.lensModel(),
                    metadata.exifExtraJson(), now));
            queuePreview(photoId, metadata, buffer);
            buffer.add(mediaRepository.resetAiFields(photoId));
            return ImportOutcome.UPDATED;
        }

        // Brand-new media: insert returns the generated ID immediately.
        long photoId = mediaRepository.insertPhoto(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(),
                metadata.captureDate(), metadata.captureDateSource(),
                metadata.orientationDegrees(), metadata.cameraMake(),
                metadata.cameraModel(), metadata.lensModel(),
                metadata.exifExtraJson(), now);
        queuePreview(photoId, metadata, buffer);
        return ImportOutcome.IMPORTED;
    }

    /**
     * Phase 1 does <b>no</b> thumbnail generation at all — see implementation plan. The only
     * per-file write beyond {@code PHOTO} itself is this quick-preview upsert, and only when
     * {@link PhotoMetadataExtractor} found an embedded EXIF preview (JPEG only) while parsing
     * metadata above — piggybacked onto the same batched flush, so it costs no extra disk writes.
     * The real thumbnail is generated later, on demand, by {@code ThumbnailGenerationJob} — only
     * for photos the UI actually asks to see.
     */
    private void queuePreview(long photoId, ExtractedMetadata metadata, List<Query> buffer) {
        if (metadata.embeddedPreviewBytes() != null) {
            buffer.add(photoPreviewRepository.upsertQuery(photoId, metadata.embeddedPreviewBytes()));
        }
    }

    private long resolveFolderId(long importRootId, Path rootPath, Path dir, Map<Path, Long> folderIdCache) {
        Long cached = folderIdCache.get(dir);
        if (cached != null) {
            return cached;
        }

        long id;
        if (dir.equals(rootPath)) {
            Path fileName = rootPath.getFileName();
            String rootName = fileName != null
                    ? fileName.toString()
                    : rootPath.toString();
            id = folderRepository.findOrCreate(importRootId, null, "", rootName);
        } else {
            long parentId = resolveFolderId(importRootId, rootPath, dir.getParent(), folderIdCache);
            String relativePath = rootPath.relativize(dir)
                                          .toString();
            id = folderRepository.findOrCreate(importRootId, parentId, relativePath,
                    dir.getFileName()
                       .toString());
        }
        folderIdCache.put(dir, id);
        return id;
    }

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
        Files.walkFileTree(rootPath, new FileCollectingVisitor(SUPPORTED_EXTENSIONS, found));
        return found;
    }

    @Override
    public String getProcessName() {
        return IMPORT_SCAN;
    }


}
