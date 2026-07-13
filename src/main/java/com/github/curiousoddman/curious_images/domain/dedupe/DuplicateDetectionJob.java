package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoHashRecord;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateJobRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Duplicate detection: a separate, user-triggered background job (never run as part of import).
 * <p>
 * Two images are duplicates when their decoded pixel content is identical AND they share the
 * same file extension — comparison never crosses file types, per the product spec ("JPEG and CR3
 * with same image -> not duplicate").
 * <p>
 * Resumability: each photo's hash is cached in PHOTO_HASH alongside the file size it was computed
 * from. A rerun only (re)hashes photos whose current file size doesn't match what's cached —
 * everything else is reused for free. This means an interrupted run isn't wasted work: whatever
 * was hashed before the interrupt stays cached for next time.
 * <p>
 * Hashing is parallelized across a configurable fixed pool of plain platform threads
 * ({@code app.duplicate-detection.thread-count}, default 4) — not virtual threads, since the work
 * is CPU-bound image decoding, not I/O-bound waiting.
 */
@Slf4j
@RequiredArgsConstructor
public class DuplicateDetectionJob extends BackgroundJob {
    public static final  String        DUPLICATE_DETECTION = "Duplicate Detection";
    public static final  AtomicInteger THREAD_COUNTER      = new AtomicInteger();
    private static final int           DB_FLUSH_BATCH_SIZE = 200;

    private final DSLContext               dsl;
    private final PhotoRepository          photoRepository;
    private final PhotoHashRepository      photoHashRepository;
    private final DuplicateJobRepository   duplicateJobRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final PixelHasher              pixelHasher;
    private final TimeProvider             timeProvider;
    private final int                      threadCount;

    @Override
    public void runImpl() {
        log.info("Starting duplicate detection");
        publishStarted("Loading photo library...");
        long jobId = -1;
        try {
            List<PhotoForHashing>      photos         = photoRepository.findAllForHashing();
            Map<Long, PhotoHashRecord> existingHashes = photoHashRepository.findAllAsMap();

            jobId = duplicateJobRepository.insertRunning(timeProvider.now(), photos.size());

            Map<Long, HashEntry>  hashByPhoto  = new HashMap<>(photos.size());
            List<PhotoForHashing> needsHashing = new ArrayList<>();
            for (PhotoForHashing photo : photos) {
                PhotoHashRecord cached = existingHashes.get(photo.id());
                if (cached != null && cached.getHashedFileSize() == photo.fileSize()) {
                    hashByPhoto.put(photo.id(), new HashEntry(photo.extension(), cached.getPixelHash()));
                } else {
                    needsHashing.add(photo);
                }
            }
            log.info("{} of {} photos need (re)hashing", needsHashing.size(), photos.size());

            AtomicInteger processed = new AtomicInteger(hashByPhoto.size());
            publishInProgress("Hashing photos...", processed.get(), photos.size());

            boolean interrupted = !needsHashing.isEmpty()
                    && hashAndPersist(needsHashing, photos.size(), processed, hashByPhoto);

            if (interrupted || isInterruptRequested()) {
                duplicateJobRepository.markInterrupted(jobId, timeProvider.now());
                log.info("Duplicate detection interrupted after hashing {} of {} photos",
                        processed.get(), photos.size());
                publishInterrupted();
                return;
            }

            Map<GroupKey, List<Long>> groups     = groupDuplicates(hashByPhoto);
            int                       groupCount = persistGroups(jobId, groups);

            duplicateJobRepository.markCompleted(jobId, timeProvider.now(), groupCount);
            log.info("Duplicate detection completed: {} duplicate group(s) found among {} photos",
                    groupCount, photos.size());
            publishEnded("Found %d duplicate group%s".formatted(groupCount, groupCount == 1 ? "" : "s"));
        } catch (Exception e) {
            log.error("Duplicate detection failed", e);
            if (jobId >= 0) {
                duplicateJobRepository.markFailed(jobId, timeProvider.now(), String.valueOf(e.getMessage()));
            }
            publishFailed(e);
            throw e;
        }
    }

    /**
     * Hashes {@code needsHashing} across a fixed pool of {@link #threadCount} threads, persisting
     * (batched) and reporting progress as each result comes back. Polls the interrupt flag once
     * per completed result, so cancellation lands promptly without needing to interrupt
     * in-flight decode work.
     *
     * @return {@code true} if the run was interrupted partway through
     */
    private boolean hashAndPersist(List<PhotoForHashing> needsHashing, int totalPhotos,
                                   AtomicInteger processed, Map<Long, HashEntry> hashByPhoto) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "duplicate-hash-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        CompletionService<PixelHasher.PhotoHashResult> completionService = new ExecutorCompletionService<>(executor);
        LocalDateTime                                  now               = timeProvider.now();

        try {
            for (PhotoForHashing photo : needsHashing) {
                completionService.submit(() ->
                        pixelHasher.hash(photo.id(), Path.of(photo.absolutePath()), photo.extension(), photo.fileSize()));
            }

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < needsHashing.size(); i++) {
                if (isInterruptRequested()) {
                    flush(buffer);
                    executor.shutdownNow();
                    return true;
                }

                PixelHasher.PhotoHashResult result;
                try {
                    result = completionService.take()
                                              .get();
                } catch (InterruptedException ie) {
                    Thread.currentThread()
                          .interrupt();
                    flush(buffer);
                    executor.shutdownNow();
                    return true;
                } catch (ExecutionException ee) {
                    log.warn("Failed to hash a photo", ee.getCause());
                    processed.incrementAndGet();
                    continue;
                }

                if (result.pixelHash() != null) {
                    hashByPhoto.put(result.photoId(), new HashEntry(result.extension(), result.pixelHash()));
                    buffer.add(photoHashRepository.upsertQuery(result.photoId(), result.pixelHash(), result.fileSize(), now));
                    if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                        flush(buffer);
                    }
                }
                // else: undecodable file (corrupt / CR2 with no usable preview) — skip silently,
                // same "don't fail the whole job over one bad file" policy as ImportService.

                int done = processed.incrementAndGet();
                publishProgressThrottled("Hashing photos", done, totalPhotos, result.absolutePath(), done == totalPhotos);
            }
            flush(buffer);
            return false;
        } finally {
            executor.shutdown();
        }
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

    private Map<GroupKey, List<Long>> groupDuplicates(Map<Long, HashEntry> hashByPhoto) {
        Map<GroupKey, List<Long>> groups = new HashMap<>();
        for (Map.Entry<Long, HashEntry> entry : hashByPhoto.entrySet()) {
            GroupKey key = new GroupKey(entry.getValue()
                                             .extension(), entry.getValue()
                                                                .pixelHash());
            groups.computeIfAbsent(key, _ -> new ArrayList<>())
                  .add(entry.getKey());
        }
        groups.values()
              .removeIf(photoIds -> photoIds.size() < 2);
        return groups;
    }

    /**
     * Inserts this run's groups, then deletes every other run's groups — the Duplicates View
     * always reflects only the latest completed run.
     */
    private int persistGroups(long jobId, Map<GroupKey, List<Long>> groups) {
        LocalDateTime now = timeProvider.now();
        return dsl.transactionResult(configuration -> {
            DSLContext ctx   = DSL.using(configuration);
            int        count = 0;
            for (Map.Entry<GroupKey, List<Long>> entry : groups.entrySet()) {
                long groupId = duplicateGroupRepository.insertGroup(
                        ctx, jobId, entry.getKey()
                                         .extension(), entry.getKey()
                                                            .pixelHash(), now);
                duplicateGroupRepository.insertMembers(ctx, groupId, entry.getValue());
                count++;
            }
            duplicateGroupRepository.deleteGroupsNotInJob(ctx, jobId);
            return count;
        });
    }

    @Override
    public String getProcessName() {
        return DUPLICATE_DETECTION;
    }

    private record GroupKey(String extension, String pixelHash) {
    }

    private record HashEntry(String extension, String pixelHash) {
    }
}
