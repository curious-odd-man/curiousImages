# Speeding up import while still showing thumbnails — implementation plan

## Context / problem

`ImportJob` currently does, per file, synchronously: metadata extraction → thumbnail
decode/rotate/resize/write → buffered DB write. At 25k images this is too slow, largely
because thumbnail generation (decode + resize + encode) happens for every file during the
scan, whether or not the user ever looks at it.

## Investigation findings (already established, don't re-derive these)

- **Not a double-decode problem.** `PhotoMetadataExtractor.extract()` uses
  `metadata-extractor`, which parses JPEG/EXIF segments, not a full pixel decode. It doesn't
  compete with `ThumbnailGenerator`'s full `BufferedImage` decode.
- **Profiling: ~60% of import time is reading (decode), ~30% is writing (resize+encode+write).**
- **File mix:** mostly JPEG, CR2 (RAW) is rare. Optimizations for CR2 are not worth the
  complexity right now.
- **Disk layout:** source photo library is on **HDD**. Thumbnail cache
  (`app.thumbnail-cache.dir`, resolved by `ThumbnailCachePaths`) is on a **separate SSD**.
  This means read/write are *not* contending for the same disk head — the read cost is
  "seeking around scattered source files on the HDD," not "alternating between two disk
  locations." Parallelizing the write side (CPU + SSD) is safe; parallelizing HDD *reads*
  is not obviously a win and needs empirical testing (start with 1 reader thread).
- **Hardware:** 6 CPU cores available, single-threaded import today, default (untuned) JVM
  heap.
- **DB:** H2, file-based.
- **UI:** JavaFX. Grid (`LibraryController`) currently builds an `ImageView` node for
  *every* photo in the selected set at once (`FlowPane`, no virtualization) — selecting a
  folder/timeline/album/search result with thousands of photos creates thousands of nodes
  immediately.
- **ControlsFX is not a project dependency**, and given JavaFX 25 is very recent, adding it
  now carries unverified-compatibility risk. Decision: **do not add it** — see grid section
  below for the chosen alternative.

## Decisions made

1. **Split `ImportJob` into two phases**, decoupling the scan from thumbnail generation:
    - **Phase 1 (scan):** walk files, extract metadata, upsert `PHOTO` rows, flush batched —
      **no thumbnail generation at all**. This is the part that runs during a big import and
      should now be fast, since it drops the 60%+30% thumbnail cost entirely.
    - **Phase 2:** doesn't exist as a bulk sweep — **no background trickle-fill**. A photo's
      real thumbnail is generated **only when requested by the UI** (see below). Confirmed
      explicitly: "no thumbnail should be generated unless it is required."

2. **Instant "quick preview" from the embedded EXIF thumbnail, stored in H2.**
    - `PhotoMetadataExtractor.extractEmbeddedPreviewBytes` currently only fires for CR2.
      Generalize it to also try for JPEG — most camera/phone JPEGs carry an
      `ExifThumbnailDirectory` (IFD1) preview, and Phase 1 has *already* opened and parsed the
      file's EXIF, so extracting this costs effectively nothing extra.
    - Store the preview bytes in a **new table**, `PHOTO_PREVIEW(photo_id, bytes)`, not a
      column on `PHOTO` — keeps existing `PhotoRepository` queries (`findByFolderId`, etc.)
      lean, since most callers don't need preview bytes. Piggyback the insert onto the
      existing batched flush (`DB_FLUSH_BATCH_SIZE = 200`) so it costs no extra disk writes.
    - CR2 (rare): skip the quick-preview shortcut for now, fall straight to the generic
      placeholder below.
    - Files with no embedded preview at all (some PNGs, CR2 without a usable preview, corrupt
      files): **generic blurred grey card + spinner/loading indicator**. No disk I/O, no
      per-file special-casing needed.

3. **On-demand real thumbnail generation, scoped to "currently visible photos," not "current
   folder."** Selection in `LibraryController` isn't just folders — it's also Timeline
   (day/month), Undated, Album, and semantic Search, each producing an arbitrary photo-ID
   set. The correct trigger point is wherever the grid is about to render a set of photo IDs,
   regardless of which selection type produced that set.

4. **Grid: hand-rolled paged loading, not a virtualization library.**
    - Keep the existing `FlowPane` + `ScrollPane` in `library.fxml` — no structural FXML
      change.
    - `populatePhotoGrid` loads a **first page** (proposed default: 200 photos — confirm/tune
      during implementation) of the selected set, not the whole set.
    - A listener on the `ScrollPane`'s `vvalueProperty()` detects proximity to the bottom and
      appends the next page (+ fires thumbnail requests for that new page).
    - Cells are never removed once created (no recycling) — acceptable tradeoff for
      simplicity; memory cost only grows if the user actually scrolls through thousands of
      photos, not merely by opening a large folder.
    - "Visible" == "belongs to a page that has been loaded so far." Slightly coarser than true
      viewport tracking (a page scrolled past still counts as requested) but avoids needing
      cell-reuse staleness tracking — only folder/selection-switch staleness matters (see
      below), not per-cell reassignment.
    - No other changes needed to slideshow ordering, thumbnail-size slider binding, or scene
      lookup — those all operate on live children exactly as today, since cells still exist as
      real, persistent nodes.

5. **`BackgroundJob` / `JobManager`: add a "supersedable" job class, instead of building a
   separate scheduler.** Explicit decision to keep the existing infrastructure (simple,
   already wired to the UI) rather than a new dedicated executor.
    - `BackgroundJob` gains `isSupersedable()`, default `false` (no behavior change for
      existing jobs).
    - `JobManager.submit()` branches: if `job.isSupersedable()`, drop any *not-yet-started*
      queued instance of the same class (a newer request already supersedes it), and if an
      instance of that class is *currently running*, call `requestInterrupt()` on it (same
      graceful-stop mechanism `ImportJob` already checks via `isInterruptRequested()`). The new
      job is enqueued normally; the worker picks it up once the superseded run exits its loop.
    - This gives "switch to generating thumbnails for the new selection immediately" semantics,
      with a small, bounded, accepted delay while the previous run finishes its current
      in-flight file (can't abort mid-decode/write) — explicitly accepted as a fine tradeoff.
    - Sketch:
      ```java
      // BackgroundJob
      public boolean isSupersedable() { return false; }
 
      // JobManager
      synchronized Optional<JobDescriptor> submit(BackgroundJob job) {
          if (shutdown) return Optional.empty();
          if (job.isSupersedable()) return submitSupersedable(job);
          // ... existing same-class-discard logic, unchanged
      }
 
      private Optional<JobDescriptor> submitSupersedable(BackgroundJob job) {
          queue.removeIf(managed -> sameClass(job, managed.job()));
          if (currentJob != null && sameClass(job, currentJob.job())) {
              currentJob.job().requestInterrupt();
          }
          return enqueue(job);
      }
      ```
      (`sameClass` needs to change signature to take two `BackgroundJob`s; existing call site
      in the old discard branch needs `.job()` added.)
    - New class: `ThumbnailGenerationJob extends BackgroundJob`, `isSupersedable() { return
     true; }`, takes the requested photo-ID set (a page from the grid). `LibraryController`
      submits it via a new `jobManager.submitThumbnailGenerationJob(photoIds)` on every
      selection change / page append.

6. **Thumbnail generation job internals — split reader role from resize/write role.**
   Since HDD reads are the seek-bound part and SSD writes are the CPU/throughput-bound part:
    - A single reader path pulls source files (sorted by path for locality) and decodes them —
      start with **1 thread** for this, since more HDD-reading threads risk *more* seek
      thrashing, not less. Measure before adding a second.
    - Resize + JPEG-encode + write-to-SSD can be parallelized across the 6 cores more safely,
      since it isn't HDD-bound.
    - Each unit of work carries the request's generation/selection ID; check it's still current
      right before starting decode (not after — can't abort an in-flight HDD read, so the goal
      is "don't start stale work," not "cancel started work").

## Known limitation — needs a decision before/during implementation

`JobManager.workerLoop` runs **one job at a time, total, across the whole app** (single
worker thread, strict serial queue) — not just "one per class." Consequence: if a bulk
`ImportJob` (or Duplicates/AI Pipeline job) is currently running, a submitted
`ThumbnailGenerationJob` will sit in the queue and not start until the running job finishes
(or is cancelled) — the "instant thumbnails for what you're browsing" behavior only applies
when nothing else is running. This wasn't resolved in this conversation. Options to consider
next session:

- Accept it: browsing during a large import shows placeholders only, until import completes.
  (Phase 1 import is now fast without thumbnail work, so this window is much shorter than
  today — may be acceptable as-is.)
- Give `JobManager` a second worker thread dedicated to supersedable/interactive jobs, while
  bulk jobs (Import, Duplicates, AI Pipeline) stay on the current serial worker. This is a
  bigger change to `JobManager` than what's designed above and needs its own design pass.

Decision: accept it

## Other small fixes to make while in this code

- `ThumbnailCachePaths`'s class javadoc says thumbnails are "sharded by `photo_id % 1000`" —
  stale; the actual implementation mirrors the full absolute source path under the configured
  cache root. Update the comment to match reality.

## Classes touched (for reference in the next session)

- `ImportJob` — split into metadata-only Phase 1; remove inline thumbnail generation call.
- `PhotoMetadataExtractor` — generalize `extractEmbeddedPreviewBytes` to attempt JPEG, not
  just CR2.
- New: `PhotoPreviewRepository` (or similar) for the `PHOTO_PREVIEW` table.
- `ThumbnailGenerator` / `SourceImageDecoder` — reused as-is for on-demand generation; may
  need minor adjustment to support the reader/writer role split.
- `ThumbnailCachePaths` — javadoc fix only.
- `BackgroundJob`, `JobManager` — add `isSupersedable()` + `submitSupersedable` path.
- New: `ThumbnailGenerationJob`.
- `LibraryController` — paged grid loading, scroll listener, submit
  `ThumbnailGenerationJob` per page/selection change, grey-card+spinner placeholder wiring,
  fallback to stored `PHOTO_PREVIEW` bytes when present.
- `library.fxml` — no structural change (kept `FlowPane` + `ScrollPane`).

## Not doing (explicitly ruled out)

- ControlsFX `GridView` — dependency + JavaFX 25 compatibility risk, not worth it given the
  simpler paged-`FlowPane` alternative covers the actual need.
- Background trickle-fill sweep for never-viewed photos — explicitly not wanted.
- CR2-specific optimization — too rare in this library to be worth the complexity right now.
