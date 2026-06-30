# Outstanding Issues — curious-images

## 🔴 Critical Bugs

### 1. `resetAiFields()` has no WHERE clause — resets the entire library
**File:** `persistence/PhotoRepository.java` · **Caller:** `ImportService.importOneFile()` (UPDATED path)

When a photo file is detected as changed (size differs), `importOneFile` calls:

```java
buffer.add(photoRepository.resetAiFields());
```

But `resetAiFields()` generates:

```java
dsl.update(PHOTO)
   .set(PHOTO.AI_FACE_DETECT_DONE, false)
   .set(PHOTO.AI_FACE_EMBED_DONE, false)
   .set(PHOTO.AI_CLIP_EMBED_DONE, false)
   .set(PHOTO.AI_LUCENE_INDEX_DONE, false);
   // ← NO WHERE clause
```

This blanket-resets every photo in the library, not just the one that changed. After a rescan that finds even a single updated file, the entire AI pipeline will re-run for every photo in the library. The intent is clearly `.where(PHOTO.ID.eq(photoId))`.

---

### 2. AI pipeline retries permanently broken photos forever
**File:** `persistence/PhotoRepository.java` — `findPendingFaceDetect/Embed/ClipEmbed`

When a photo fails AI processing, `markErrorQuery` increments `ai_retry_count` but leaves all `ai_*_done` flags as `false`. None of the `findPending*` queries filter by `ai_retry_count` or `ai_last_error`. A corrupt/undecodable file will be reattempted on every pipeline run, indefinitely, wasting time and generating misleading log noise. There is no max-retry cap or "skip permanently broken" path.

---

### 3. `InterruptBackgroundProcessEvent` interrupts all running jobs simultaneously
**File:** `util/async/AbstractBackgroundJob.java`

```java
@EventListener
public void requestInterrupt(InterruptBackgroundProcessEvent event) {
    shouldInterrupt = true;
}
```

Every `AbstractBackgroundJob` subclass listens to the same untagged event. If the user clicks "Cancel" while, say, duplicate detection is running, it will also flip `shouldInterrupt = true` on `ImportService` and `AiPipelineJob` — even if neither is running. The code relies on the UI "never starting two jobs at once," but the event bus carries no job identifier, making this fragile and documented as a known debt in comments.

---

## 🟠 Logic / Design Issues

### 4. `AddFilesService` fires AI pipeline and duplicate detection while still holding the `finish()` call
**File:** `domain/imports/AddFilesService.java` — line with `FIXME` comment

```java
publishEnded("Files added successfully");

// FIXME: These 2 will fire progress events -> ui will be broken.
if (request.runAiPipeline()) {
    applicationEventPublisher.publishEvent(new RunAiPipelineEvent(this));
}
if (request.runDuplicateDetection()) {
    duplicateDetectionService.start();
}
```

The author left a FIXME acknowledging this. `AddFilesService` calls `publishEnded` and then immediately fires two more background jobs. Both jobs publish their own progress events. The UI progress component doesn't know which job to display, so whichever came last wins — the user could see the UI show "AI pipeline 0%" right after "Files added successfully." The fix (noted in the comment) would be a single-threaded executor or a job queue.

### 5. k-means initialisation uses first-k points (not k-means++)
**File:** `domain/ai/AlbumGenerationService.java` — `kMeans()`

```java
for (int c = 0; c < k && c < n; c++) centroids[c] = Arrays.copyOf(data[c], dims);
```

Centroids are seeded from the first `k` CLIP embeddings in DB load order (import order). Photos imported around the same time are visually similar, so the initial centroids are not spread across the embedding space. This makes the algorithm prone to poor clusters and slow convergence. k-means++ or even random sampling would be significantly better.

### 6. `buildEventAlbums` uses wall-clock milliseconds to compare `LocalDateTime` timestamps
**File:** `domain/ai/AlbumGenerationService.java`

```java
long gap = java.time.Duration.between(prev.getCaptureDate(), next.getCaptureDate()).toMillis();
if (gap > gapMillis) { ... }
```

`LocalDateTime` has no timezone. EXIF capture dates are typically stored in local time (device time zone). If a photo was taken near midnight and a trip crosses a timezone boundary, or if the device clock was wrong, the gap calculation produces silently incorrect album splits. There is no guard for negative gaps (photos with capture_date out of order relative to DB load order — `ORDER BY capture_date` handles this, but only if capture_dates are trustworthy).

### 7. `PersonClusteringService` Pass 2 does not start new clusters for faces that fall below threshold
**File:** `domain/ai/PersonClusteringService.java`

During Pass 2 reassignment:

```java
int newCluster = (bestCluster == -1) ? clusterOf[i] : bestCluster;
```

A face that no longer exceeds the similarity threshold for any centroid (`bestCluster == -1`) is kept in its old cluster silently instead of being demoted to a singleton or triggering a new cluster. This means after reassignment, some clusters may contain faces that don't actually meet the threshold against the final centroid.

### 8. Location album names are raw coordinates, not human-readable place names
**File:** `domain/ai/AlbumGenerationService.java` — `buildLocationAlbums()`

```java
String name = entry.getKey(); // e.g. "48.85,2.35"
```

This is noted in a comment as "legible enough as a placeholder," but there is no path to upgrade it — no reverse-geocoding call, no TODO with a plan. Users will see albums named `"48.85,2.35"` with no indication of city or country.

### 9. `DuplicateResolutionService` deletes the photo from DB before confirming the group is cleaned up
**File:** `domain/dedupe/DuplicateResolutionService.java`

Each photo is deleted in its own transaction:

```java
dsl.transaction(cfg -> {
    duplicateGroupRepository.deleteMember(ctx, groupId, photo.getId());
    thumbnailRepository.deleteByPhotoId(ctx, photo.getId());
    photoRepository.deleteById(ctx, photo.getId());
});
deletedPhotoIds.add(photo.getId());
```

Then the group is checked in a second transaction. If the application crashes between the two transactions, the group may be left with a single (or zero) member — permanently shown in the UI as a "duplicate group" with only one photo, with no way to clean it up short of re-running detection.

### 10. `findPendingClipEmbed` does not gate on `ai_face_detect_done`
**File:** `persistence/PhotoRepository.java`

```java
.where(PHOTO.AI_CLIP_EMBED_DONE.eq(false))
// ← no face_detect_done filter
```

CLIP and face detection are independent pipelines (intentional per design), but a photo could be face-detected, then its file deleted, then CLIP embedding attempted — and fail with a file-not-found error. This doesn't cause data corruption but does produce avoidable errors. A minor clarity concern.

---

## 🟡 Minor / Observability

### 11. `BPE` merge loop only merges the best pair starting from `bestIdx`, not all occurrences
**File:** `domain/ai/ClipTokenizer.java` — `bpe()` method

The merge loop has a subtle bug: after finding `bestIdx`, it merges from that position forward, but the `while` loop inside checks `symbols.get(i).equals(symbols.get(bestIdx))` rather than checking the pair. If the same pair appears at index 0 and index 5, only the one at `bestIdx` (and subsequent contiguous occurrences after it) is merged. Occurrences before `bestIdx` are skipped. The Python reference implementation iterates the entire list on each step. This may cause tokenisation results to differ from the reference CLIP tokenizer, degrading semantic search quality.

### 12. `duplicate_group` has a `UNIQUE (extension, pixel_hash)` constraint but groups are rebuilt per-job
**File:** `db/migration/V005__duplicate_schema.sql`

Each `duplicate_group` row belongs to a `duplicate_job_id`, but the unique constraint `(extension, pixel_hash)` is across all jobs. When `persistGroups` tries to insert a group for the new job, if that `(extension, pixel_hash)` pair already exists from the previous run, it will violate the constraint before `deleteGroupsNotInJob` cleans up the old rows — because insert happens before delete. The code may rely on the transaction to protect this, but the ordering is fragile.
