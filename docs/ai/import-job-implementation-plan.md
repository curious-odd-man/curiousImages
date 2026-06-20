# Implementation Plan: Photo Import Job

> **Purpose of this document:** a self-sufficient implementation plan for the *Import* feature
> of "cImages", a JavaFX/Spring Boot/jOOQ desktop photo manager. It embeds everything a fresh
> session needs to know about the **existing codebase** (it was not built from scratch — it's an
> in-progress project, originally scaffolded from a music-library app and being repurposed for
> photos), plus the concrete design for the import pipeline. You should not need the original
> `src.zip` or `build_prompt.md` to act on this document — only the actual project checkout.

---

## 1. What already exists (read this before writing any code)

The project lives under base package `com.github.curiousoddman.curious_images`.

**Stack confirmed from the source tree:** Java 25, JavaFX 25, Spring Boot (full DI container, not
a hand-rolled DI framework), jOOQ 3.19.28 against H2 (`SQLDialect.H2`), Flyway migrations, Lombok,
Gradle. Tests use JUnit 5 + Awaitility + Hamcrest. The H2 database is file-based:
`jdbc:h2:file:~/cimages/${DATABASE_FILE};DB_CLOSE_ON_EXIT=FALSE`, with `DATABASE_FILE` set per
Spring profile (`cimages-dev` / `cimages-prod`).

**Important context:** the codebase was originally a *music* library app
(`curious_images` is the artifact name; UI controllers still reference `currentTrackAlbumImage`,
`buttonPlayPause`, `artistList`, etc.). It is mid-repurposing into a photo manager. Don't be
surprised by leftover music-player FXML/fields — they are not part of this task and should be
left alone except where explicitly noted below. **No build.gradle was available when this plan
was written** — the exact jOOQ codegen Gradle task name must be located in the real project
(commonly `generateJooq` via the `nu.studer.jooq` plugin, or jOOQ's own codegen plugin) before
step 5 can be executed.

### 1.1 Current package layout

```
com.github.curiousoddman.curious_images/
├── ApplicationMain.java            # Spring ApplicationRunner: runs all StartupRunnable beans
├── JavafxApplication.java          # JavaFX Application bootstrap, owns Spring context
├── Main.java                       # main(), launches JavafxApplication w/ AnimatedPreloader
├── app/preloader/                  # splash screen plumbing (irrelevant to import)
├── config/
│   ├── ApplicationConfig.java      # StageManager bean
│   ├── DataSourceConfig.java       # DataSource / jOOQ DSLContext beans
│   ├── FxmlLoader.java             # Spring-aware FXML loader (controllerFactory = context::getBean)
│   ├── FxmlView.java               # typed registry of FXML views -> controller classes
│   └── StageManager.java           # scene switching
├── dbobj/                          # jOOQ-GENERATED CODE — do not hand-edit
│   ├── DefaultCatalog.java, Indexes.java, Keys.java, Public.java, Tables.java
│   └── tables/ (+ tables/records/)  # one class per DB table, e.g. PendingAction, UserPreferences
├── domain/
│   ├── DataAccess.java              # current hand-written jOOQ repository (user prefs only so far)
│   ├── ExceptionTranslator.java     # jOOQ ExecuteListener -> Spring DataAccessException
│   ├── tags/FilesScanningService.java   # <-- the import job stub, see 1.3 below
│   └── user/prefs/                  # UserPrefKey enum + UserPreferencesService (window state)
├── event/                           # Spring ApplicationEvent based pub/sub, see 1.2
├── model/
│   ├── LoadedFxml.java               # record(Parent, Controller)
│   └── bundle/RescanBundle.java      # ListResourceBundle carrying the chosen folder into the modal
├── retryable/actions/                # generic durable/retryable action framework, see 1.4
├── ui/controller/screen/
│   ├── LibraryController.java        # main window controller (mostly leftover music UI)
│   └── RescanLibraryController.java  # folder-picker modal, already wired end-to-end
└── util/                             # FileUtils, StartupRunnable, TimeProvider, VersionService,
                                       # util/async/DelayedAction, util/styles/CssClasses
```

```
src/main/resources/
├── application.yaml / application-dev.yaml / application-prod.yaml
├── db/migration/V001__permissions.sql, V002__init.sql
├── fxml/library.fxml, rescan-modal.fxml, preloader.fxml
├── img/noimage.png                   # <-- existing placeholder image for missing thumbnails
└── styles/global.css
```

### 1.2 Event system (reuse this, don't invent a new one)

All cross-cutting communication (background jobs -> UI) goes through Spring's
`ApplicationEventPublisher`/`@EventListener`, with a custom multicaster that logs every dispatch
and a custom publisher that logs every publish. Relevant classes, verbatim:

```java
// event/types/BackgroundProcessEventType.java
public enum BackgroundProcessEventType {
    STARTED(false), IN_PROGRESS(false), INTERRUPTED(true), FAILED(true), ENDED(true);
    private final boolean isTerminal;
}
```

```java
// event/BackgroundProcessEvent.java
@Getter
@Builder
public class BackgroundProcessEvent extends ApplicationEvent {
    private final Object source;
    private final String processName;
    private final String description;
    private final int progress;
    private final int maxProgress;
    private final Exception error;
    private final BackgroundProcessEventType eventType;
    // constructor mirrors fields, calls super(source)
}
```

`ApplicationEvent` (the superclass) already carries a `getTimestamp()` (epoch millis at
construction) for free — **use this for "elapsed time" instead of adding a new field.**

```java
// event/InterruptBackgroundProcessEvent.java  — generic cancellation signal, source = whoever asked
public class InterruptBackgroundProcessEvent extends ApplicationEvent { ... }

// event/RescanLibraryEvent.java — fired by the folder-picker modal with the chosen path
@Getter
public class RescanLibraryEvent extends ApplicationEvent {
    private final String path;
}
```

### 1.3 The import job stub that already exists

`domain/tags/FilesScanningService.java` is the **starting point for this task**. It already:
- listens for `RescanLibraryEvent` and spins up a `Thread` to run the scan off the FX thread,
- walks the chosen folder recursively (`Files.walkFileTree`, collecting **all** regular files
  into a `List<Path>` first — this is fine at 25k-file scale, see §13),
- publishes `BackgroundProcessEvent`s for STARTED / IN_PROGRESS / INTERRUPTED / FAILED / ENDED,
- listens for `InterruptBackgroundProcessEvent` and sets a `shouldInterrupt` flag checked each
  iteration,
- has a single unimplemented hook: `extractMetadataAndUpdateDatabase(Path file)` — **this is
  the whole gap this plan fills.**

Full current content (package `domain.tags`, will be **moved and rewritten**, see §6):

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FilesScanningService {
    public static final String LIBRARY_SCAN = "Library Scan";
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DataAccess dataAccess;
    private boolean shouldInterrupt;

    @EventListener
    public void onRescanEvent(RescanLibraryEvent event) {
        String libraryRoot = event.getPath();
        shouldInterrupt = false;
        Runnable rescanRunnable = () -> {
            publishStartedEvent();
            try {
                List<Path> paths = doScan(Path.of(libraryRoot));
                publishInProgressEvent(paths);
                for (int i = 0; i < paths.size(); i++) {
                    if (shouldInterrupt) { publishInterruptedEvent(); return; }
                    extractMetadataAndUpdateDatabase(paths.get(i)); // FIXME — empty
                    publishInProgressStatusEvent(i, paths);
                }
                publishCompletedEvent();
            } catch (Exception e) {
                publishFailedEvent(e);
            }
        };
        new Thread(rescanRunnable, "rescan").start();
    }

    @EventListener
    public void onInterruptBackgroundProcess(InterruptBackgroundProcessEvent event) {
        shouldInterrupt = true;
    }
    // doScan(...) via Files.walkFileTree + SimpleFileVisitor, collects regular files only
    // publishXxxEvent(...) helpers build BackgroundProcessEvent via a shared builder
}
```

**Two pre-existing bugs to fix while rewriting this class** (call them out explicitly in the PR):
1. The `try/catch` wraps the **entire loop** — one corrupt/unreadable file aborts the whole
   import. Each file's processing must be isolated.
2. Nothing prevents a second `RescanLibraryEvent` from starting a second concurrent scan thread
   while one is already running.

### 1.4 UI entry point (already wired, reuse as-is)

`ui/controller/screen/RescanLibraryController.java` + `fxml/rescan-modal.fxml` already implement
a complete "pick a folder, click Scan" flow that publishes `RescanLibraryEvent`:

```java
@FXML public void onChoosePath(ActionEvent e) { /* DirectoryChooser -> sets path TextField */ }
@FXML public void onRescan(ActionEvent e) {
    eventPublisher.publishEvent(new RescanLibraryEvent(this, path.getText()));
    ((Stage) path.getScene().getWindow()).close();
}
```

It's opened from `LibraryController.onRescanMenuClicked`, which loads
`FxmlView.RESCAN_MODAL` with a `RescanBundle` (a `ListResourceBundle` carrying the last-used
path, currently hardcoded to `"D:\\My Pictures"` — leave that as-is, it's a placeholder pending
the "select a root folder" / persisted-IMPORT_ROOT feature, out of scope here). **Nothing in this
flow needs to change** except `LibraryController.onBackgroundProcessEvent`, see §11.

### 1.5 Generic retryable-action framework (available, NOT required for this phase)

`retryable/actions/` implements a durable, JSON-serialized, retry-on-startup job queue backed by
table `pending_action` (`type`, `payload` bytes, `status`, `retry_count`, `next_attempt_at`,
`last_error`). `DurableActionService` (a `StartupRunnable`) replays any `PENDING`/stuck
`IN_PROGRESS` rows on every app launch, dispatching by payload class name to a registered
`DurableActionsHandler<T>`. This exists for **per-item resumable retries of small individual
operations**. It is *not* used by this plan — the import job is a single long-running scan, not a
queue of independent durable actions — but the **duplicate-detection job** (a later phase) is an
excellent candidate for this exact framework, since the build prompt explicitly asks for
"resumable design preferred" there. Mentioned here only so a fresh session doesn't reinvent it.

### 1.6 jOOQ generated-code convention (this is what new tables will look like)

No hand-written SQL outside Flyway migrations. Generated classes follow this shape (excerpt of
`dbobj/tables/records/PendingActionRecord.java`, generated from `V002__init.sql`):

```java
public class PendingActionRecord extends UpdatableRecordImpl<PendingActionRecord> {
    public void setId(Long value) { set(0, value); }
    public Long getId() { return (Long) get(0); }
    // ... one getter/setter pair per column, types inferred from the SQL DDL
    public PendingActionRecord(Long id, String type, byte[] payload, ...) { ... }
}
```

and table constants land in `dbobj/Tables.java`:

```java
public static final PendingAction PENDING_ACTION = PendingAction.PENDING_ACTION;
```

Hand-written repositories use the static import style already established in `DataAccess`:

```java
import static com.github.curiousoddman.curious_images.dbobj.Tables.USER_PREFERENCES;
// ...
dsl.mergeInto(USER_PREFERENCES)
   .using(dsl.selectOne())
   .on(USER_PREFERENCES.PREF_KEY.eq(key.getKey()))
   .whenMatchedThenUpdate().set(USER_PREFERENCES.PREF_VALUE, val(value))
   .whenNotMatchedThenInsert(USER_PREFERENCES.PREF_KEY, USER_PREFERENCES.PREF_VALUE)
   .values(val(key.getKey()), val(value))
   .execute();
```

New repositories for this feature must follow the same pattern (constructor-injected
`DefaultDSLContext`/`DSLContext`, `@Component`/`@Repository`, no Spring Data magic).

### 1.7 Existing migrations (for ID/type conventions)

```sql
-- V001__permissions.sql
GRANT ALL ON SCHEMA public TO sa;

-- V002__init.sql
CREATE TABLE pending_action (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         VARBINARY,
    status          VARCHAR(30)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    last_error      TEXT
);
CREATE TABLE IF NOT EXISTS user_preferences (
    pref_key   VARCHAR(255) NOT NULL PRIMARY KEY,
    pref_value VARCHAR(255)
);
```

Conventions to keep: lowercase snake_case table/column names, `BIGSERIAL PRIMARY KEY`,
`TIMESTAMP` (not `TIMESTAMPTZ`) for all datetimes (matches `TimeProvider#now()` returning
`LocalDateTime`).

### 1.8 Other reusable pieces

- `util/TimeProvider` — `@Component` wrapping `LocalDateTime.now()`, already used by the durable
  action DAO so timestamps are testable/mockable. **Use it instead of calling `LocalDateTime.now()`
  directly** anywhere the import pipeline needs to stamp rows.
- `util/StartupRunnable` + `ApplicationMain` — any bean implementing this interface runs once at
  app launch. Not needed for the import job itself (it's user-triggered), but worth knowing about.
- `img/noimage.png` — already-bundled placeholder image resource. **Reuse this** for the grid/UI
  whenever a thumbnail can't be produced, instead of inventing a new placeholder.
- Logging: `DelegatingApplicationEventPublisher` and `CustomApplicationEventMulticaster` already
  log every published event and every listener invocation — no need to add manual logging around
  event publication.

---

## 2. Scope of this phase

**In scope:** the full import pipeline — folder scan, metadata extraction, thumbnail generation,
persistence, progress/cancellation, idempotent rescans.

**Explicitly out of scope** (later phases per the original product spec): Folder Tree view, Grid
view, Timeline view, Duplicate Detection job, deleting/editing photos, the full Library UI
rebuild. The only UI work in this plan is the minimal progress/cancel wiring needed to satisfy
the functional requirement that the import job "expose progress, current file, elapsed time,
cancellation" — see §11.

---

## 3. New dependencies (add to Gradle)

| Dependency | Purpose |
|---|---|
| `com.drewnoakes:metadata-extractor` (2.19.0 or later) | EXIF/TIFF/PNG metadata + embedded EXIF thumbnail extraction. TIFF-based, so it also parses Canon CR2. Pure Java, no native deps — fine for the Windows-only target. |
| `net.coobird:thumbnailator` (0.4.20 or later) | Simple, dependency-light high-quality resizing with aspect-ratio preservation; wraps `ImageIO` + `Graphics2D` so we don't hand-roll scaling math. |

No new test dependencies — reuse the existing JUnit 5 + Awaitility + Hamcrest setup (see
`util/async/DelayedActionTest.java` for the established async-assertion style with `await()`).

---

## 4. Database schema — new migration `V003__import_schema.sql`

```sql
CREATE TABLE import_root
(
    id              BIGSERIAL PRIMARY KEY,
    path            VARCHAR(1024) NOT NULL UNIQUE,
    created_at      TIMESTAMP     NOT NULL,
    last_scanned_at TIMESTAMP
);

-- The import root itself is represented as a FOLDER row too (relative_path = '',
-- parent_folder_id = NULL), so every PHOTO can simply reference a folder_id without
-- a special case for "files directly in the root".
CREATE TABLE folder
(
    id               BIGSERIAL PRIMARY KEY,
    import_root_id   BIGINT        NOT NULL REFERENCES import_root (id),
    parent_folder_id BIGINT REFERENCES folder (id),
    relative_path    VARCHAR(1024) NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    UNIQUE (import_root_id, relative_path)
);

CREATE TABLE photo
(
    id                  BIGSERIAL PRIMARY KEY,
    folder_id           BIGINT        NOT NULL REFERENCES folder (id),
    absolute_path       VARCHAR(2048) NOT NULL UNIQUE,
    filename            VARCHAR(512)  NOT NULL,
    extension           VARCHAR(10)   NOT NULL,
    file_size           BIGINT        NOT NULL,
    image_width         INT,
    image_height        INT,
    capture_date        TIMESTAMP,
    capture_date_source VARCHAR(20),  -- EXIF_ORIGINAL | EXIF_DIGITIZED | FILESYSTEM
    imported_at         TIMESTAMP     NOT NULL,
    last_seen_at        TIMESTAMP     NOT NULL
);
CREATE INDEX idx_photo_folder ON photo (folder_id);
CREATE INDEX idx_photo_capture_date ON photo (capture_date);

-- Deliberately no image bytes stored here — only the cache path. See §10.
CREATE TABLE thumbnail
(
    photo_id     BIGINT PRIMARY KEY REFERENCES photo (id),
    cache_path   VARCHAR(2048) NOT NULL,   -- relative to the configured thumbnail cache root
    width        INT           NOT NULL,
    height       INT           NOT NULL,
    generated_at TIMESTAMP     NOT NULL
);
```

Notes / deliberate simplifications for MVP:
- `absolute_path UNIQUE` is what makes rescans idempotent — see §12.
- `last_seen_at` is included now (cheap) so a future "mark missing files" pass doesn't require
  another migration, but **this phase does not implement removal/missing-file handling** — the
  build prompt doesn't ask for delete/missing detection yet, only re-scan.
- `DUPLICATE_*` tables from the original product spec are intentionally **not** created here —
  they belong to the separate duplicate-detection phase.

---

## 5. jOOQ codegen regeneration

After adding the migration:
1. Run the project's jOOQ codegen Gradle task against the (Flyway-migrated) H2 schema — locate
   the actual task name in `build.gradle`/`build.gradle.kts` (commonly `generateJooq` if using
   the `nu.studer.jooq` plugin). The existing generated sources under `dbobj/` show the exact
   target package/style (`com.github.curiousoddman.curious_images.dbobj`, `renderSchema=false`,
   catalog `public`) to expect as output — confirm the regenerated `Tables.java` now also lists
   `IMPORT_ROOT`, `FOLDER`, `PHOTO`, `THUMBNAIL` alongside the existing `PENDING_ACTION` and
   `USER_PREFERENCES`.
2. Commit the regenerated sources (this project checks generated jOOQ code into the repo, per
   the existing `dbobj/` tree — don't `.gitignore` it).
3. Sanity check: `dbobj.Tables.PHOTO.ABSOLUTE_PATH`, `dbobj.tables.records.PhotoRecord`, etc.
   should now exist and compile.

---

## 6. New/changed package layout

```
domain/imports/                          # NEW — replaces domain/tags/
│   ImportService.java                   # was FilesScanningService — rewritten, see §8
│
domain/imports/metadata/                 # NEW
│   PhotoMetadataExtractor.java
│   ExtractedMetadata.java               # record(width, height, captureDate, captureDateSource)
│   CaptureDateSource.java               # enum EXIF_ORIGINAL, EXIF_DIGITIZED, FILESYSTEM
│
domain/imports/thumbnail/                # NEW
│   ThumbnailGenerator.java
│   ThumbnailCachePaths.java             # resolves/shards the on-disk cache path
│
persistence/                             # NEW top-level package — hand-written repositories,
│   ImportRootRepository.java            # separate from jOOQ-generated dbobj/
│   FolderRepository.java
│   PhotoRepository.java
│   ThumbnailRepository.java
```

`domain/tags/FilesScanningService.java` is deleted; its single constant `LIBRARY_SCAN` and class
name are referenced from exactly one other place — `LibraryController` — update that import (see
§11). Renaming to `ImportService` / `IMPORT_SCAN` is recommended now since this is the first real
implementation of the class; if you'd rather minimize the diff, keep both names unchanged and
just move packages — either is fine, just be consistent.

A `persistence` package is introduced (didn't exist before — only the single `DataAccess` class
under `domain` existed) because this feature needs four real repositories with non-trivial
queries; bundling them into `DataAccess` would make that class unmanageable. `DataAccess` itself
is untouched — it stays as the user-preferences repository.

---

## 7. Event model change

Add one nullable field to the existing `BackgroundProcessEvent` to carry "current file" (the
"elapsed time" requirement is already covered for free by `ApplicationEvent#getTimestamp()` —
the UI subtracts the STARTED event's timestamp from "now"):

```java
@Getter
@Builder
public class BackgroundProcessEvent extends ApplicationEvent {
    private final Object source;
    private final String processName;
    private final String description;
    private final int progress;
    private final int maxProgress;
    private final Exception error;
    private final BackgroundProcessEventType eventType;
    private final String currentItem;   // NEW — absolute path of the file currently being processed

    public BackgroundProcessEvent(Object source, String processName, String description,
                                   int progress, int maxProgress, Exception error,
                                   BackgroundProcessEventType eventType, String currentItem) {
        super(source);
        this.source = source; this.processName = processName; this.description = description;
        this.progress = progress; this.maxProgress = maxProgress; this.error = error;
        this.eventType = eventType; this.currentItem = currentItem;
    }
}
```

This is backward compatible: it's a `@Builder`, so every other existing call site that doesn't
set `currentItem` simply gets `null`. No other listener needs to change.

---

## 8. `ImportService` — algorithm

```java
package com.github.curiousoddman.curious_images.domain.imports;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportService {
    public static final String IMPORT_SCAN = "Import Scan";
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "cr2");
    private static final int PROGRESS_PUBLISH_INTERVAL_MS = 100;
    private static final int DB_FLUSH_BATCH_SIZE = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final ImportRootRepository importRootRepository;
    private final FolderRepository folderRepository;
    private final PhotoRepository photoRepository;
    private final ThumbnailRepository thumbnailRepository;
    private final PhotoMetadataExtractor metadataExtractor;
    private final ThumbnailGenerator thumbnailGenerator;
    private final TimeProvider timeProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean shouldInterrupt;

    @EventListener
    public void onRescanEvent(RescanLibraryEvent event) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Import already running, ignoring new RescanLibraryEvent for {}", event.getPath());
            return; // fixes bug #2 from §1.3 — no overlapping scans
        }
        shouldInterrupt = false;
        new Thread(() -> runImport(event.getPath()), "import-scan").start();
    }

    @EventListener
    public void onInterruptBackgroundProcess(InterruptBackgroundProcessEvent event) {
        shouldInterrupt = true;
    }

    private void runImport(String rootPath) {
        publishStarted();
        int imported = 0, skipped = 0, errors = 0;
        try {
            long importRootId = importRootRepository.findOrCreate(rootPath, timeProvider.now());
            // folder relative path -> folder id, avoids re-querying/re-inserting per file in the same dir
            Map<Path, Long> folderIdCache = new HashMap<>();
            List<Path> files = scan(Path.of(rootPath));               // existing doScan() logic, extension-filtered
            publishInProgress(files.size(), null);

            List<PendingWrite> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (shouldInterrupt) { flush(buffer); publishInterrupted(); return; }
                Path file = files.get(i);
                try {
                    ImportOutcome outcome = importOneFile(rootPath, importRootId, folderIdCache, file, buffer);
                    if (outcome == ImportOutcome.SKIPPED_UNCHANGED) skipped++; else imported++;
                } catch (Exception e) {
                    errors++;
                    log.error("Failed to import {}", file, e);   // fixes bug #1 from §1.3 — isolate per-file failures
                }
                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) flush(buffer);
                maybePublishProgress(i, files.size(), file);
            }
            flush(buffer);
            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            publishEnded(imported, skipped, errors);
        } catch (Exception e) {
            publishFailed(e);
        } finally {
            running.set(false);
        }
    }
    // importOneFile(...): folder upsert (via folderIdCache + FolderRepository.findOrCreate),
    //   PhotoRepository.findByAbsolutePath() to decide skip-vs-reprocess (§12), metadata extraction,
    //   thumbnail generation, queue PHOTO+THUMBNAIL upserts into `buffer` (not written immediately —
    //   see §13 for why), return SKIPPED_UNCHANGED or IMPORTED.
    // flush(buffer): dsl.batch(buffer.stream().map(PendingWrite::toQuery).toList()).execute() inside one
    //   transaction, then buffer.clear().
    // maybePublishProgress(...): only publish if >= PROGRESS_PUBLISH_INTERVAL_MS elapsed since last publish,
    //   OR this is the last file — prevents flooding the FX event queue across 25k files (§13).
    // scan(...): same Files.walkFileTree as today's doScan(), plus a SUPPORTED_EXTENSIONS filter — this filter
    //   is also what implements "ignore sidecar files" (.xmp/.thm/etc. simply never match the whitelist).
    // publishXxx(...): same builder pattern as today, now also setting currentItem where relevant.
}
```

Key decisions baked into the above (state these explicitly in code review / commit message):
- **Single dedicated thread, no thread pool.** The work is disk-I/O bound; a pool adds
  concurrency complexity (out of step with "prioritize simplicity... over premature
  optimization") without a clear win for local-disk sequential reads. Revisit only if real-world
  profiling on a 25k-photo Windows folder shows the scan is unacceptably slow.
- **Per-file isolation.** A single corrupt JPEG or unreadable CR2 must not abort the whole job —
  catch, log, count as an error, continue.
- **Re-entrancy guard.** `AtomicBoolean running` rejects a second concurrent `RescanLibraryEvent`
  instead of starting two scans against the same DB.
- **Batched + throttled, not per-file, side effects.** DB writes are buffered and flushed via
  jOOQ `batch(...)` every 200 photos (or at end of job); UI progress events are time-throttled,
  not fired once per file. See §13 for the full rationale.

---

## 9. Metadata extraction (`PhotoMetadataExtractor`)

```java
public record ExtractedMetadata(Integer width, Integer height,
                                 LocalDateTime captureDate, CaptureDateSource captureDateSource) {}

public enum CaptureDateSource { EXIF_ORIGINAL, EXIF_DIGITIZED, FILESYSTEM }
```

Algorithm, using `com.drew.imaging.ImageMetadataReader.readMetadata(file)`:

1. **Capture date**, in priority order (mirrors the product spec exactly):
   1. `ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL` → `CaptureDateSource.EXIF_ORIGINAL`
   2. `ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED` (this is what's conventionally called
      "CreateDate") → `CaptureDateSource.EXIF_DIGITIZED`
   3. `Files.getLastModifiedTime(path)` → `CaptureDateSource.FILESYSTEM`
2. **Width/height**: prefer `ExifIFD0Directory`/`ExifSubIFDDirectory`
   `TAG_EXIF_IMAGE_WIDTH`/`TAG_EXIF_IMAGE_HEIGHT` (full-resolution sensor dimensions — this is
   what the spec means by "image width/height", distinct from the 512px thumbnail). If absent
   (common for plain JPEGs without those specific tags), fall back to `JpegDirectory` width/height
   for `.jpg`/`.jpeg`, or `PngDirectory`'s IHDR width/height for `.png`. If still absent for a
   CR2 (rare), fall back to the dimensions of whatever embedded preview was extracted in step 3.
3. **Embedded preview bytes (CR2 only — for thumbnail generation, not stored as metadata)**:
   - Primary: `ExifThumbnailDirectory.hasThumbnailData()` / `getThumbnailData()`. This works
     reliably for JPEG and for CR2 on **some** versions of metadata-extractor — there is a
     **known regression** in the library (drewnoakes/metadata-extractor#149) where
     `hasThumbnailData()` started returning `false` for CR2 files in 2.8.1+ even though the
     thumbnail tags are present. **Verify against a real CR2 sample file with the exact pinned
     library version before relying on this path.**
   - Fallback if the primary path returns no data but `TAG_THUMBNAIL_COMPRESSION` /
     `TAG_THUMBNAIL_OFFSET` / `TAG_THUMBNAIL_LENGTH` are present: read the JPEG bytes directly —
     for a raw TIFF-based file like CR2 the TIFF header starts at byte 0, so
     `offset = thumbnailDirectory.getInteger(TAG_THUMBNAIL_OFFSET)` is the absolute file offset;
     read `length = getInteger(TAG_THUMBNAIL_LENGTH)` bytes from there via `RandomAccessFile`/
     `FileChannel`.
   - If both fail (no embedded thumbnail at all): treat the photo as having **no thumbnail
     available** rather than failing the whole import — the THUMBNAIL row is simply not created,
     and the UI falls back to the existing `img/noimage.png` placeholder (§10).
   - **Deliberately deferred to a future enhancement**: shelling out to a bundled `exiftool.exe`
     for more reliable/larger CR2 previews. Flagged here so it isn't silently forgotten, but the
     MVP should ship with the pure-Java `metadata-extractor` path only, per "prioritize
     simplicity."

For JPEG/PNG, no special preview extraction is needed — `ImageIO.read(file)` decodes them
directly for thumbnail generation (§10).

Write a small unit-test fixture set (`src/test/resources/fixtures/`) with one known-good JPEG,
PNG and CR2 sample with verifiable EXIF values, and assert the priority-order logic and
width/height extraction against them.

---

## 10. Thumbnail cache (`ThumbnailGenerator` + `ThumbnailCachePaths`)

- **Cache root**: new config property, e.g. `app.thumbnail-cache.dir`, defaulting to
  `~/cimages/thumb-cache` (sibling to the existing `~/cimages/${DATABASE_FILE}` H2 file — same
  pattern already used for the DB path in `application.yaml`). Add it next to the existing
  `spring.datasource.url` entry.
- **Deterministic path**: shard by `PHOTO.id` to avoid one huge flat directory:
  `{cacheRoot}/{id % 1000}/{id}.jpg`. Store the **relative** part (`{id % 1000}/{id}.jpg`) in
  `THUMBNAIL.cache_path` so the cache root can be relocated later via config alone, without a
  data migration.
- **Generation pipeline** (same code path regardless of source format — this is what "do not
  perform full RAW rendering" means in practice: CR2 never goes through a raw decoder, only its
  already-JPEG-encoded embedded preview does):
  1. Obtain a `BufferedImage` — `ImageIO.read(file)` for JPEG/PNG, or
     `ImageIO.read(new ByteArrayInputStream(embeddedPreviewBytes))` for CR2 (bytes from §9 step 3).
  2. `Thumbnails.of(image).size(512, 512).keepAspectRatio(true).outputFormat("jpg").toFile(cachePath)`
     (Thumbnailator's `size(w, h)` with `keepAspectRatio(true)` constrains the **longest** edge to
     the given box while preserving aspect ratio — exactly the "longest edge = 512px" requirement).
  3. Create parent directories on first write per shard (`Files.createDirectories`).
- **"Regenerate if cache entry missing"**: implement this as `ensureThumbnail(photoId)` — checks
  `Files.exists(resolvedPath)`, and if not, re-derives the cache path and regenerates from the
  source file recorded in `PHOTO.absolute_path`. The import job itself always generates fresh, so
  it won't usually hit this path, but write it as a standalone reusable method now — the future
  Grid view will call it on every thumbnail load, and it directly satisfies the spec's explicit
  "regenerate if cache entry missing" requirement.
- **No image blobs in H2** — confirmed nowhere in this design does a `BLOB`/`VARBINARY` column
  hold pixel data; only file paths are persisted, satisfying that constraint explicitly.

---

## 11. Minimal UI wiring for progress / current file / elapsed / cancel

The functional requirement ("expose progress, current file, elapsed time, cancellation") needs
*some* visible UI, even though the full Grid/Library UI is out of scope. Reuse the existing
`BackgroundProcessEvent` listener in `LibraryController` rather than building new screens:

```java
@EventListener
public void onBackgroundProcessEvent(BackgroundProcessEvent event) {
    if (!event.getProcessName().equals(ImportService.IMPORT_SCAN)) return;
    runOnFxThread(() -> {
        importProgressLabel.setText(event.getProgress() + " / " + event.getMaxProgress());
        importCurrentFileLabel.setText(event.getCurrentItem());
        long elapsedMs = System.currentTimeMillis() - event.getTimestamp();
        importElapsedLabel.setText(Duration.ofMillis(elapsedMs).toString());
        importCancelButton.setVisible(!event.getEventType().isTerminal());
        if (event.getEventType().isTerminal()) {
            // future hook: refresh Grid/Folder Tree views once they exist
        }
    });
}

@FXML
public void onCancelImport(ActionEvent e) {
    eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
}
```

Add three labels + a cancel button to `library.fxml` (a small status bar is enough — don't touch
the existing leftover music-player nodes like `artistList`/`currentTrackAlbumImage`, they're
unrelated and will be removed wholesale when the real Grid/Library UI is built in a later phase).
`importCancelButton` publishes the existing `InterruptBackgroundProcessEvent` — no new
cancellation mechanism is needed, the import job already listens for it (§8).

---

## 12. Rescan / idempotency behavior

- `PHOTO.absolute_path` is `UNIQUE`. On each file: `PhotoRepository.findByAbsolutePath(path)`
  first.
  - **Not found** → full insert (metadata + thumbnail generated).
  - **Found, `file_size` unchanged** → cheap path: just update `last_seen_at`, skip metadata
    re-extraction and thumbnail regeneration entirely (this is what makes a rescan of an
    unchanged 25k-photo library fast).
  - **Found, `file_size` changed** → treat as modified: re-extract metadata, regenerate
    thumbnail, update the row.
- **Known limitation, accepted for MVP**: a same-size content edit (e.g. EXIF rewritten by
  another tool without changing the byte count) won't be detected. Upgrading the check to also
  compare filesystem mtime is a one-line addition (`Files.getLastModifiedTime`) if this proves
  insufficient in practice — not done now to keep the first version simple.
- Folder rows are upserted by `(import_root_id, relative_path)`, so re-running a scan never
  duplicates folder rows either.

---

## 13. Performance notes for the 25,000-photo target

- **Streaming-ish, not fully streaming, file discovery.** `Files.walkFileTree` still collects
  the full file list before processing starts (kept from the existing `doScan`), because the
  progress bar needs a `maxProgress` total up front. At 25k files this is a few MB of `Path`
  objects — trivial memory-wise — but it does mean there's a brief "discovering files..." stall
  before the per-file progress bar starts moving on a very large/slow (e.g. network or
  USB-attached) tree. Acceptable for MVP; if it becomes a UX problem, switch to an indeterminate
  progress bar during discovery (already supported — `publishStarted()` sets `maxProgress = -1`
  today) and keep this as-is otherwise.
- **Batched DB writes.** Buffer photo/thumbnail upserts and flush via jOOQ's `batch(...)` API
  every 200 items (or at job end) inside one transaction per flush, instead of one round trip per
  photo. This is the concrete fulfillment of "process images in streaming/batched fashion."
- **Throttled progress events.** Publishing a Spring `ApplicationEvent` (which gets dispatched
  through `Platform.runLater` via `runOnFxThread`) for every single one of 25,000 files risks
  flooding the JavaFX Application Thread's event queue and making the UI feel laggy even though
  the background thread itself is fine. Only publish if ≥100ms have passed since the last publish,
  or it's the final file — this directly satisfies "the UI must remain responsive."
- **No image collection held in memory.** Each file's `BufferedImage` is decoded, thumbnailed,
  and immediately discarded (no list of decoded images is ever assembled) — satisfies "avoid
  loading full image collections into memory."

---

## 14. Testing strategy

- `PhotoMetadataExtractorTest` — against committed fixture files (one JPEG with both
  DateTimeOriginal and DateTimeDigitized set, one JPEG with neither set so the filesystem
  fallback is exercised, one PNG, one CR2), asserting the priority order and width/height logic
  from §9.
- `ThumbnailGeneratorTest` — assert longest-edge=512 and aspect-ratio preservation for a
  landscape and a portrait input image, and that the deterministic shard path is reproducible for
  a given photo id.
- `FolderRepositoryTest` / `PhotoRepositoryTest` — against the real H2 dialect (an
  `application-test.yaml` profile pointed at an in-memory or throwaway file H2 instance; none
  exists yet in the resources reviewed for this plan — only `dev`/`prod` — add one).
- `ImportServiceTest` — end-to-end against a temp directory fixture with a handful of files,
  using the existing `await()`/Awaitility style from `DelayedActionTest` to assert a terminal
  `BackgroundProcessEvent(IMPORT_SCAN, ENDED)` is eventually published, and that re-running the
  scan with no file changes is a no-op (all `SKIPPED_UNCHANGED`).
- Manual end-to-end pass before calling this done: point at a real mixed JPEG/PNG/CR2 folder,
  run once, run again (verify near-instant rescan), cancel mid-scan (verify partial progress is
  committed via the last completed batch flush and the thread actually stops), confirm thumbnail
  files exist on disk at the expected shard paths and `noimage.png` is shown for any CR2 without
  an extractable preview.

---

## 15. Ordered implementation checklist

1. Add `metadata-extractor` and `thumbnailator` Gradle dependencies.
2. Add `V003__import_schema.sql`; regenerate jOOQ sources; commit generated code (§4–5).
3. Implement `persistence/{ImportRootRepository, FolderRepository, PhotoRepository,
   ThumbnailRepository}` with unit tests against H2.
4. Implement `domain/imports/metadata/{PhotoMetadataExtractor, ExtractedMetadata,
   CaptureDateSource}` with fixture-based unit tests (§9, §14).
5. Implement `domain/imports/thumbnail/{ThumbnailGenerator, ThumbnailCachePaths}` with unit
   tests (§10, §14). Add the `app.thumbnail-cache.dir` config property.
6. Add the `currentItem` field to `BackgroundProcessEvent` (§7) — purely additive, no other
   listener changes required.
7. Delete `domain/tags/FilesScanningService.java`; add `domain/imports/ImportService.java`
   implementing §8 (folder upsert + metadata + thumbnail + batched persistence + throttled
   events + cancellation + per-file error isolation + re-entrancy guard).
8. Update `LibraryController`'s import of `FilesScanningService.LIBRARY_SCAN` →
   `ImportService.IMPORT_SCAN`; add the progress/current-file/elapsed/cancel status bar (§11) and
   wire `onCancelImport`.
9. Write `ImportServiceTest` end-to-end test (§14).
10. Manual end-to-end pass (§14) against a real Windows folder with a JPEG/PNG/CR2 mix.

---

## 16. Open decisions to confirm with the user before/while implementing

- Exact jOOQ codegen Gradle task name — couldn't be confirmed, `build.gradle` wasn't part of the
  reviewed source export. 
  Answer: jooqCodegen
- Whether to rename `FilesScanningService`/`LIBRARY_SCAN` → `ImportService`/`IMPORT_SCAN` (recommended)
  or keep the old names and only move the package, to minimize diff noise. 
  Answer: do rename
- Whether bundling `exiftool.exe` for stronger CR2 preview extraction should be pulled into this
  phase or stay deferred (recommendation: defer, see §9).
  Answer: defer
- Config key name/default for the thumbnail cache root (`app.thumbnail-cache.dir` proposed).
  Answer: ok
- Whether "missing on rescan" detection (file deleted from disk since last scan) should be added
  now given `last_seen_at` already exists, or deferred — recommendation: defer, not in the
  original functional requirements for Import.
  Answer: defer
