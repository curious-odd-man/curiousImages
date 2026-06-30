# E2E Integration Test — Implementation Guide

Everything a new chat session needs to write all tests without asking questions.

---

## 1. Project Overview

**curious-images** is a JavaFX desktop photo organiser backed by Spring Boot + JOOQ + H2 (PostgreSQL-compatibility mode) + Apache Lucene. It has no HTTP layer. The "e2e" boundary is: **boot the real Spring context, drive it through the same entry points the UI uses (event publishing + direct service calls), and assert the resulting DB and Lucene state**.

Package root: `com.github.curiousoddman.curious_images`

---

## 2. Tech Stack (versions from `build.gradle`)

- Java 25, Spring Boot 4.0.0, `io.spring.dependency-management` 1.1.7
- JOOQ 3.19.28 (generated classes in subpackage `dbobj`)
- H2 2.4.240 (`MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH`)
- Flyway 11.19.1 (migrations: `src/main/resources/db/migration/`)
- Apache Lucene 9.12.1 (HNSW KNN, 512-dim float vectors)
- Lombok, Awaitility (already on test classpath via `spring-boot-starter-test`)
- Mockito (already on test classpath via `spring-boot-starter-test`)
- ONNX Runtime GPU 1.21.0 (`onnxruntime_gpu` — do **not** add `onnxruntime` CPU-only, it conflicts)

Build tool: Gradle. Test task already configured with required JVM `--add-reads`/`--add-opens` for JavaFX modules.

---

## 3. Existing Test Infrastructure (reuse / extend these)

| Class | Location | Purpose |
|---|---|---|
| `H2TestDatabase` | `test/.../test/H2TestDatabase.java` | Spins up a fresh UUID-named H2 in-memory DB, runs real Flyway migrations, returns a `DSLContext`. Used by existing unit-style tests. **Not used by the new e2e tests** — the Spring context manages its own datasource. |
| `FakeTimeProvider` | `test/.../imports/FakeTimeProvider.java` | `TimeProvider` that advances by 1 s per call. **Inject this as a `@Primary` bean** in the e2e test configuration to make timestamp assertions deterministic. |
| `RecordingEventPublisher` | `test/.../imports/RecordingEventPublisher.java` | Records all `ApplicationEvent`s. **Do not use** in the new tests — the real Spring `ApplicationEventPublisher` must be used so that `@EventListener` methods on real beans fire correctly. |
| Fixture images | `src/test/resources/fixtures/` | `with-exif-dates.jpg` (800×600, EXIF date 2023-06-15T14:30:00), `no-exif-dates.jpg` (640×480, no EXIF), `plain.png` (320×240), `portrait.jpg` (600×800). These are real decodable images used for import, thumbnail generation, and duplicate hashing. |

---

## 4. The Four Mocked Beans

These are the **only** beans that must be mocked. Every other bean is the real production implementation.

### Why they must be mocked

| Bean | Class | Reason |
|---|---|---|
| `ModelPaths` | `domain.ai.ModelPaths` | Has `@PostConstruct verifyModelsExist()` which tries to extract ONNX files from the classpath (`/models/*.onnx`). These are hundreds-of-MB files that are not bundled in the JAR for tests. It throws `IllegalStateException` if missing, crashing context startup. |
| `RetinaFaceDetector` | `domain.ai.RetinaFaceDetector` | Loads and runs ONNX session on `detect(BufferedImage)`. Requires real `.onnx` file on disk. |
| `ArcFaceEncoder` | `domain.ai.ArcFaceEncoder` | Loads and runs ONNX session on `encode(BufferedImage)`. Requires real `.onnx` file on disk. |
| `ClipImageEncoder` | `domain.ai.ClipImageEncoder` | Loads and runs ONNX session on `encode(BufferedImage)`. Requires real `.onnx` file on disk. |

**Note:** `OnnxModelRegistry` is also a `@Component` that creates an `OrtEnvironment` eagerly. It does NOT need a mock — `OrtEnvironment.getEnvironment()` works fine without model files. Sessions are loaded lazily via `getOrLoad()` which is never called when the three detector/encoder beans above are mocked.

**Note:** `ClipTextEncoder` encodes text queries (used by `SearchService`). It also uses `OnnxModelRegistry` lazily. For the Search test class, mock `ClipTextEncoder` too (same approach as the others).

### Method signatures to stub

```java
// RetinaFaceDetector
List<DetectedFace> detect(BufferedImage image) throws OrtException

// ArcFaceEncoder  
float[] encode(BufferedImage alignedFace) throws OrtException

// ClipImageEncoder
float[] encode(BufferedImage image) throws OrtException

// ClipTextEncoder (for Search tests only)
float[] encode(String text) throws OrtException
```

`DetectedFace` is a record:
```java
public record DetectedFace(float x, float y, float w, float h,
                           float confidence, float[][] landmarks) {}
```

### Synthetic return values

```java
// One realistic detected face (centred in image, 5 landmarks)
static final DetectedFace ONE_FACE = new DetectedFace(
    0.3f, 0.2f, 0.4f, 0.5f, 0.99f,
    new float[][]{{100,80},{140,80},{120,110},{105,140},{135,140}}
);

// A fixed 512-dim L2-normalised unit vector (all equal components)
static float[] fixedEmbedding(float value) {
    float[] v = new float[512];
    Arrays.fill(v, value);
    float norm = (float) Math.sqrt(512 * value * value);
    for (int i = 0; i < v.length; i++) v[i] /= norm;
    return v;
}
// Use fixedEmbedding(1.0f) for all faces/CLIP embeddings unless the test
// specifically needs differentiated vectors (e.g. clustering tests).
```

---

## 5. Test Application Configuration (`application-test.yaml`)

The file already exists at `src/test/resources/application-test.yaml` with only:
```yaml
DATABASE_FILE: cimages-test
H2_CONSOLE_ENABLED: false
```

**Replace it entirely** with the content below. The new tests inject temp dirs via system properties set in a `@BeforeAll` (see §7).

```yaml
spring:
  datasource:
    # Each test class sets the 'test.db.name' system property to a UUID
    # so classes never share state even if run in parallel.
    url: "jdbc:h2:mem:${test.db.name:cimages-test};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH"
    driver-class-name: org.h2.Driver
    username: sa
    password: sa
  flyway:
    enabled: true
  h2:
    console:
      enabled: false
  main:
    web-application-type: none  # skip servlet container entirely

app:
  thumbnail-cache:
    dir: "${test.thumb.dir:/tmp/cimages-test-thumbs}"
  ai:
    model-dir: "${test.model.dir:/tmp/cimages-test-models}"
    index-root: "${test.index.dir:/tmp/cimages-test-index}"
    execution-provider: CPU
    intra-op-threads: 1
    batch-size: 1
    event-gap-hours: 6
    min-event-size: 5
    min-location-size: 3
    min-cluster-size: 10
    min-cluster-similarity: 0.6

H2_CONSOLE_ENABLED: false
logging:
  level:
    org.jooq: warn
    com.github.curiousoddman: info
```

**Key points:**
- `web-application-type: none` prevents servlet container startup (the app has `web-application-type: servlet` in production but only to serve the H2 console — not needed in tests).
- Temp dir system properties are set per class in `@BeforeAll` so each test class gets isolated Lucene indices and thumbnail caches.
- `DB_CLOSE_DELAY=-1` keeps the H2 in-memory DB alive for the duration of the JVM.

---

## 6. Shared `@TestConfiguration` — `E2ETestConfiguration.java`

Create this once in `src/test/java/.../e2e/`:

```java
package com.github.curiousoddman.curious_images.e2e;

import com.github.curiousoddman.curious_images.domain.ai.*;
import com.github.curiousoddman.curious_images.domain.imports.FakeTimeProvider; // existing class
import com.github.curiousoddman.curious_images.util.TimeProvider;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@TestConfiguration
public class E2ETestConfiguration {

    // ── Deterministic clock ───────────────────────────────────────────────────
    @Bean
    @Primary
    public TimeProvider fakeTimeProvider() {
        return new FakeTimeProvider(); // already exists in test sources
    }

    // ── AI model mocks ────────────────────────────────────────────────────────
    @Bean
    @Primary
    public ModelPaths modelPaths(AiConfig aiConfig) {
        // Return a no-op ModelPaths — verifyModelsExist() must not throw
        return new ModelPaths(aiConfig) {
            @Override
            public void verifyModelsExist() { /* no-op in tests */ }
        };
    }

    @Bean
    @Primary
    public RetinaFaceDetector retinaFaceDetector() throws Exception {
        RetinaFaceDetector mock = Mockito.mock(RetinaFaceDetector.class);
        DetectedFace face = new DetectedFace(
            0.3f, 0.2f, 0.4f, 0.5f, 0.99f,
            new float[][]{{100,80},{140,80},{120,110},{105,140},{135,140}}
        );
        when(mock.detect(any())).thenReturn(List.of(face));
        return mock;
    }

    @Bean
    @Primary
    public ArcFaceEncoder arcFaceEncoder() throws Exception {
        ArcFaceEncoder mock = Mockito.mock(ArcFaceEncoder.class);
        when(mock.encode(any())).thenReturn(unitVector(512, 1.0f));
        return mock;
    }

    @Bean
    @Primary
    public ClipImageEncoder clipImageEncoder() throws Exception {
        ClipImageEncoder mock = Mockito.mock(ClipImageEncoder.class);
        when(mock.encode(any())).thenReturn(unitVector(512, 1.0f));
        return mock;
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    static float[] unitVector(int dims, float fill) {
        float[] v = new float[dims];
        Arrays.fill(v, fill);
        double norm = Math.sqrt(dims * (double) fill * fill);
        for (int i = 0; i < dims; i++) v[i] /= (float) norm;
        return v;
    }
}
```

---

## 7. Base Class for All E2E Test Classes

```java
package com.github.curiousoddman.curious_images.e2e;

import com.github.curiousoddman.curious_images.Main;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@SpringBootTest(
    classes = Main.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Import(E2ETestConfiguration.class)
public abstract class AbstractE2ETest {

    @Autowired
    protected DSLContext dsl;

    /**
     * Truncate all application tables before each test method.
     * Order matters: FK-dependents first. Adjust if the schema changes.
     */
    @BeforeEach
    void clearDatabase() {
        dsl.transaction(cfg -> {
            var ctx = DSL.using(cfg);
            // FK order: children before parents
            ctx.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : List.of(
                "album_photo", "album",
                "face_embedding", "face_thumbnail", "face",
                "clip_embedding",
                "person",
                "duplicate_group_member", "duplicate_group", "duplicate_job",
                "photo_hash",
                "thumbnail", "photo", "folder", "import_root",
                "user_preferences"
            )) {
                ctx.execute("TRUNCATE TABLE " + table);
            }
            ctx.execute("SET REFERENTIAL_INTEGRITY TRUE");
        });
    }
}
```

**Important:** The Spring context is started **once per test class** (Spring's default for `@SpringBootTest`). `@BeforeEach` truncates tables to isolate individual test methods cheaply without restarting the context.

Each concrete test class must set unique system properties for its temp dirs in a `@BeforeAll` static method:

```java
@BeforeAll
static void setUpDirs(@TempDir Path tempDir) throws IOException {
    System.setProperty("test.db.name", UUID.randomUUID().toString());
    System.setProperty("test.thumb.dir", tempDir.resolve("thumbs").toString());
    System.setProperty("test.model.dir", tempDir.resolve("models").toString());
    System.setProperty("test.index.dir", tempDir.resolve("index").toString());
    Files.createDirectories(tempDir.resolve("thumbs"));
    Files.createDirectories(tempDir.resolve("models"));
    Files.createDirectories(tempDir.resolve("index"));
}
```

---

## 8. Key Entry Points — How to Drive Each Service

### Import
```java
// Single root via event
applicationEventPublisher.publishEvent(new RescanLibraryEvent(this, rootPath.toString()));

// Multi-root
importService.startMultiRootScan(List.of(path1.toString(), path2.toString()));

// Wait for completion
awaitEnded(importService); // see §9
```

### AddFilesService
```java
AddFilesRequest req = new AddFilesRequest(
    List.of(sourcePath.toString()),
    copyToDestination,          // true = copy then scan; false = register in-place
    destinationFolder.toString(), // null if copyToDestination == false
    runAiPipeline,
    runDuplicateDetection
);
boolean started = addFilesService.start(req);
// started == false means already running; assert that if testing rejection
awaitEnded(addFilesService);
```

### AI Pipeline
```java
applicationEventPublisher.publishEvent(new RunAiPipelineEvent(this));
awaitEnded(aiPipelineJob);
// AiPipelineJob completes, then synchronously calls personClusteringService.cluster()
// then publishes RegenerateAlbumsEvent, which is handled by AlbumGenerationService
// which publishes AiPipelineCompleteEvent when done
```

### Album Generation (standalone, without AI pipeline)
```java
applicationEventPublisher.publishEvent(new RegenerateAlbumsEvent(this));
// AlbumGenerationService.onRegenerateAlbums() is synchronous — no waiting needed
// but it publishes AiPipelineCompleteEvent on completion
```

### Duplicate Detection
```java
duplicateDetectionService.start();
awaitEnded(duplicateDetectionService);
```

### Duplicate Resolution
```java
// Load photo records first
List<PhotoRecord> photos = dsl.selectFrom(PHOTO).where(...).fetch();
DuplicateResolutionService.Result result = duplicateResolutionService.resolve(groupId, photos);
// result.deletedPhotoIds(), result.failures()
```

### Cancellation (interrupt)
```java
applicationEventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
// Note: this broadcasts to ALL AbstractBackgroundJob listeners simultaneously (known bug)
```

### Search
```java
// semanticSearch requires CLIP embeddings in Lucene index (populated by AI pipeline)
List<Long> results = searchService.semanticSearch("sunset", 5);

List<Long> similar = searchService.similarPhotos(photoId, 10);

List<Long> combined = searchService.combinedSearch(personId, "beach", 5);
```

### User Preferences
```java
dataAccess.setUserPref(UserPrefKey.WINDOW_X, "200");
String val = dataAccess.getUserPref(UserPrefKey.WINDOW_X, "100");
```

### PersonClusteringService (standalone)
```java
personClusteringService.cluster();
// synchronous, no waiting needed
```

---

## 9. Waiting for Background Jobs (Awaitility)

All background jobs run on daemon threads. Use Awaitility to wait for terminal events.

```java
import static org.awaitility.Awaitility.await;
import java.time.Duration;

// Pattern: wait until the service's isRunning() flips back to false
// (finish() is called in the finally block of every job)
void awaitFinished(AbstractBackgroundJob job) {
    await().atMost(Duration.ofSeconds(30))
           .until(() -> !job.isRunning());
}

// Alternative: listen for BackgroundProcessEvent with type ENDED / INTERRUPTED / FAILED
// Register a RecordingEventPublisher as an extra @EventListener if needed,
// but checking isRunning() is simpler and sufficient for most tests.
```

`AbstractBackgroundJob.isRunning()` is `public` — accessible from tests.

---

## 10. Asserting DB State

Use the injected `DSLContext dsl` directly. JOOQ generated classes are in the `dbobj` package.

```java
import static com.github.curiousoddman.curious_images.dbobj.Tables.*;

// Count photos
assertEquals(3, dsl.fetchCount(PHOTO));

// Check a specific photo
var photo = dsl.selectFrom(PHOTO)
               .where(PHOTO.ABSOLUTE_PATH.eq(path.toString()))
               .fetchOne();
assertNotNull(photo);
assertTrue(photo.getAiFaceDetectDone());

// Check albums
var albums = dsl.selectFrom(ALBUM).where(ALBUM.ALBUM_TYPE.eq("EVENT")).fetch();
assertEquals(2, albums.size());

// Check members
var members = dsl.selectFrom(ALBUM_PHOTO).where(ALBUM_PHOTO.ALBUM_ID.eq(albumId)).fetch();

// Check duplicate groups
var groups = dsl.selectFrom(DUPLICATE_GROUP).fetch();
```

Key JOOQ table names (from generated `Tables` class):
`PHOTO`, `FOLDER`, `IMPORT_ROOT`, `THUMBNAIL`, `FACE`, `FACE_EMBEDDING`, `CLIP_EMBEDDING`, `PERSON`, `ALBUM`, `ALBUM_PHOTO`, `DUPLICATE_GROUP`, `DUPLICATE_GROUP_MEMBER`, `DUPLICATE_JOB`, `PHOTO_HASH`, `USER_PREFERENCES`

Key column accessors (camelCase from snake_case DB columns):
- `PHOTO.AI_FACE_DETECT_DONE`, `PHOTO.AI_FACE_EMBED_DONE`, `PHOTO.AI_CLIP_EMBED_DONE`, `PHOTO.AI_LUCENE_INDEX_DONE`
- `PHOTO.AI_RETRY_COUNT`, `PHOTO.AI_LAST_ERROR`
- `PHOTO.FILE_SIZE`, `PHOTO.ABSOLUTE_PATH`, `PHOTO.LAST_SEEN_AT`
- `PHOTO.CAPTURE_DATE`, `PHOTO.GPS_LAT`, `PHOTO.GPS_LON`
- `FACE.PHOTO_ID`, `FACE.PERSON_ID`
- `PERSON.NAME`, `PERSON.COVER_FACE_ID`
- `ALBUM.ALBUM_TYPE`, `ALBUM.NAME`, `ALBUM.COVER_PHOTO_ID`
- `DUPLICATE_GROUP.EXTENSION`, `DUPLICATE_GROUP.PIXEL_HASH`, `DUPLICATE_GROUP.JOB_ID`

---

## 11. Fixture Images and Populating Test Library Dirs

Existing fixtures at `src/test/resources/fixtures/`:
- `with-exif-dates.jpg` — 800×600, EXIF capture date **2023-06-15T14:30:00**
- `no-exif-dates.jpg` — 640×480, no EXIF (falls back to filesystem mtime)
- `plain.png` — 320×240 PNG
- `portrait.jpg` — 600×800 portrait JPEG

Copy them into a `@TempDir` for each test that needs a real import:

```java
@TempDir Path libraryRoot;

void populateLibrary() throws IOException {
    Path fixtures = Path.of("src/test/resources/fixtures");
    Files.copy(fixtures.resolve("with-exif-dates.jpg"), libraryRoot.resolve("a.jpg"));
    Files.copy(fixtures.resolve("no-exif-dates.jpg"),   libraryRoot.resolve("b.jpg"));
    Files.copy(fixtures.resolve("plain.png"),           libraryRoot.resolve("c.png"));
}
```

For **duplicate detection tests**, two files with identical pixel content must exist. The easiest way is to copy the same fixture twice under different filenames:

```java
Files.copy(fixtures.resolve("with-exif-dates.jpg"), libraryRoot.resolve("copy1.jpg"));
Files.copy(fixtures.resolve("with-exif-dates.jpg"), libraryRoot.resolve("copy2.jpg"));
```

For **event album tests**, use `Files.setLastModifiedTime` on copies if EXIF dates aren't available, or create them with known capture dates by writing minimal valid JPEG files — however the simplest approach is to **insert PHOTO rows directly** into the DB after import, overriding `capture_date` with known `LocalDateTime` values using JOOQ:

```java
// After import, manually set capture_date for deterministic event album grouping
dsl.update(PHOTO)
   .set(PHOTO.CAPTURE_DATE, LocalDateTime.of(2024, 6, 1, 10, 0))
   .where(PHOTO.FILENAME.eq("a.jpg"))
   .execute();
```

For **location album tests**, set GPS columns directly:
```java
dsl.update(PHOTO).set(PHOTO.GPS_LAT, 48.85).set(PHOTO.GPS_LON, 2.35)
   .where(PHOTO.FILENAME.eq("a.jpg")).execute();
```

For **person clustering tests**, insert FACE and FACE_EMBEDDING rows manually after import + AI pipeline (or skip the pipeline and insert directly). Two embeddings with dot-product > 0.4 form a cluster; orthogonal vectors stay separate. Use `E2ETestConfiguration.unitVector(512, 1.0f)` for "same person" and a vector with opposite signs for "different person".

---

## 12. The Known Failing Test (UC-03)

UC-03 — "Modified photo resets AI flags for that photo only" — is **expected to fail** until the `resetAiFields()` bug is fixed. Mark it:

```java
@Test
@Disabled("Known bug: resetAiFields() has no WHERE clause — resets all photos. Issue #1.")
void rescanOfModifiedPhotoResetsAiFlagsForThatPhotoOnly() { ... }
```

Or use `assertThrows` / `assertFalse` to document the current broken behaviour and guard against accidental "fixes" that don't actually fix the root cause.

---

## 13. Test Class Map (one class per domain, ~50 tests total)

| Class | Package | Autowired beans needed |
|---|---|---|
| `ImportE2ETest` | `e2e.imports` | `ImportService`, `ApplicationEventPublisher`, `DSLContext` |
| `AddFilesE2ETest` | `e2e.imports` | `AddFilesService`, `ApplicationEventPublisher`, `DSLContext` |
| `AiPipelineE2ETest` | `e2e.ai` | `AiPipelineJob`, `ApplicationEventPublisher`, `DSLContext`, `RetinaFaceDetector` (verify mock calls) |
| `PersonClusteringE2ETest` | `e2e.ai` | `PersonClusteringService`, `DSLContext`, `FaceEmbeddingRepository`, `PersonRepository` |
| `AlbumGenerationE2ETest` | `e2e.ai` | `AlbumGenerationService`, `ApplicationEventPublisher`, `DSLContext` |
| `DuplicateDetectionE2ETest` | `e2e.dedupe` | `DuplicateDetectionService`, `DSLContext` |
| `DuplicateResolutionE2ETest` | `e2e.dedupe` | `DuplicateResolutionService`, `DSLContext`, `DuplicateGroupRepository` |
| `SearchE2ETest` | `e2e.search` | `SearchService`, `AiPipelineJob`, `ApplicationEventPublisher`, `DSLContext`, `ClipTextEncoder` (mock) |
| `UserPrefsE2ETest` | `e2e.prefs` | `DataAccess`, `DSLContext` |

For `SearchE2ETest`, add `ClipTextEncoder` to the `@Primary` mocks in `E2ETestConfiguration`:
```java
@Bean @Primary
public ClipTextEncoder clipTextEncoder() throws Exception {
    ClipTextEncoder mock = Mockito.mock(ClipTextEncoder.class);
    when(mock.encode(any(String.class))).thenReturn(E2ETestConfiguration.unitVector(512, 1.0f));
    return mock;
}
```

---

## 14. Handling `DuplicateResolutionService` — Desktop.moveToTrash

`DuplicateResolutionService.resolve()` calls `Desktop.getDesktop().moveToTrash(file)`. In a headless CI environment `Desktop.isDesktopSupported()` returns `false`, which causes all photos to be reported as failures immediately.

Two options:
1. **Preferred**: Create the test files in `@TempDir`, call `resolve()`, and assert `result.failures()` contains the expected entries when desktop is not supported. This tests the failure path (UC-42).
2. For the success path (UC-40, UC-41): subclass or spy on `DuplicateResolutionService` in a `@TestConfiguration` override to replace the `moveToTrash` call with a simple file delete. Or: only run these tests on platforms where `Desktop.isDesktopSupported()` is `true`, with `assumeTrue(Desktop.isDesktopSupported())`.

The simplest approach is `assumeTrue`:
```java
@BeforeEach
void requireDesktopSupport() {
    assumeTrue(Desktop.isDesktopSupported() &&
               Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH),
               "Skipping: OS does not support moveToTrash");
}
```

---

## 15. Import Root and Folder Wiring

`ImportService` requires the scanned path to exist on disk. Always use `@TempDir` for the library root. The `RescanLibraryEvent` constructor takes `(Object source, String path)`:

```java
applicationEventPublisher.publishEvent(
    new RescanLibraryEvent(this, libraryRoot.toString())
);
```

---

## 16. AI Pipeline → Album Generation Event Chain

The full chain when `RunAiPipelineEvent` is published:

1. `AiPipelineJob.onRunAiPipeline()` → runs on thread `"ai-pipeline"`
2. After face/CLIP embedding + Lucene indexing: calls `personClusteringService.cluster()` synchronously
3. Publishes `RegenerateAlbumsEvent`
4. `AlbumGenerationService.onRegenerateAlbums()` is an `@EventListener` — it runs on the same thread (Spring's default multicaster uses the event publisher's thread unless configured otherwise; check `CustomApplicationEventMulticaster` — it uses a task executor for listeners that support async execution, otherwise invokes synchronously)
5. Publishes `AiPipelineCompleteEvent`

For tests that only need albums without running the full AI pipeline (e.g. album generation tests with hand-crafted DB state), publish `RegenerateAlbumsEvent` directly. `AlbumGenerationService.onRegenerateAlbums()` is synchronous (no background thread) — no Awaitility needed.

---

## 17. FaceAligner — Additional Mock Consideration

`FaceAligner` crops faces from `BufferedImage` using the detected landmarks. It is a plain `@Component` with no ONNX dependency — it uses Java2D only and will work in tests as-is. **Do not mock it.** The synthetic `DetectedFace` landmarks provided by the mocked `RetinaFaceDetector` are valid pixel coordinates that `FaceAligner` can process.

---

## 18. Lucene Index Between Tests

Each test class gets its own Lucene index directory (set via `test.index.dir` system property in `@BeforeAll`). The `IndexWriter` and `SearcherManager` beans are singletons within the Spring context (shared across all test methods in one class). The `@BeforeEach` truncates DB tables but **does NOT clear the Lucene index**.

For test classes that write to the Lucene index (AI pipeline, search), either:
- Accept that index state accumulates across methods (tests that check result counts must account for previously indexed photos), or
- Use `@DirtiesContext(methodMode = BEFORE_EACH_TEST_METHOD)` on those test classes at the cost of slower context restarts, or
- Design test methods to be independent of specific counts (e.g. assert "contains photo X" rather than "exactly 1 result").

The recommended approach is to keep AI pipeline / search tests fully independent by importing a fresh set of photos in each test method and asserting inclusion rather than exact counts.

---

## 19. `@SpringBootTest` and JavaFX UI Beans

JavaFX controller classes (`LibraryController`, `DuplicatesController`, etc.) are annotated `@Component @Lazy`. They will **not** be eagerly instantiated, so no JavaFX Toolkit is needed. `FxmlLoader` is also `@Component` (not lazy) but only uses `ApplicationContext` — it does not access the JavaFX scene graph at construction time. The `StageManager` bean is `@Lazy` and only created when a `Stage` is injected — which never happens in tests.

**Do not** add `@MockBean` for any JavaFX-specific beans — the lazy wiring handles them. If a context startup failure mentions a JavaFX class, add that specific bean to `E2ETestConfiguration` as a no-op mock.

---

## 20. Known Issues to Test Against (from `01_outstanding_issues.md`)

Tests UC-03 and UC-20 directly expose confirmed bugs and should be marked with `@Disabled` or have assertion logic that documents the broken behaviour, not silently passes.

| Issue | UC | Expected test outcome |
|---|---|---|
| `resetAiFields()` has no WHERE clause | UC-03 | FAIL until fixed — mark `@Disabled("Bug #1")` |
| Permanently broken photos retried forever | UC-20 | FAIL until fixed — mark `@Disabled("Bug #2")` |
