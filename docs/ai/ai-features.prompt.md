# AI Features Implementation Plan — curiousImages

## Context

This document is a self-contained implementation plan for adding AI-powered features to the
**curiousImages** desktop photo organiser.

### Existing stack (do not change)

| Layer | Technology |
|---|---|
| Language | Java 25 |
| UI | JavaFX 25 + FXML |
| DI / services | Spring Boot 4.0 |
| Database | H2 embedded, accessed via jOOQ 3.19 |
| Schema migrations | Flyway 11 |
| Build | Gradle (Groovy DSL) |
| Image I/O | `metadata-extractor 2.20`, `thumbnailator 0.4.21` |
| Async | Plain `Thread` + Spring `ApplicationEventPublisher` (pattern already used in `ImportService` and `DuplicateDetectionService`) |

### Existing migration baseline

The last applied migration is **V005**. All new migrations must start at **V006** and increment
by one per file.

### Key conventions already in the codebase

- **Repositories** are hand-written jOOQ classes (no Spring Data JPA). Follow the same pattern.
- **Background jobs** extend `AbstractBackgroundJob` and publish `BackgroundProcessEvent` for
  progress. Follow the same pattern for every new long-running job.
- **Batched DB writes** — repository methods return unexecuted `Query` objects; callers buffer
  them and flush via `dsl.transaction(cfg -> DSL.using(cfg).batch(buffer).execute())`.
- **Threading** — all DB/AI work runs on daemon threads; results are dispatched to the JavaFX
  thread via `runOnFxThread(...)`.
- **No data migration needed** — the application is in active development with no production
  data to preserve.

---

## Overview of features to implement

1. ONNX Runtime integration (shared inference engine)
2. Face detection (RetinaFace)
3. Face embedding + clustering → persons (ArcFace)
4. CLIP image embeddings
5. Apache Lucene vector index
6. Semantic text-to-image search
7. Similar-photo discovery
8. Automatic album generation (person, event, location, visual-similarity)

Each section below describes: what to build, which files to create or change, and the exact
interfaces to use. Code snippets are illustrative — complete them as needed.

---

## Phase 1 — ONNX Runtime integration

### 1.1 build.gradle changes

Add the following to the `dependencies` block. Use CPU-only ONNX Runtime by default; GPU
support is wired in Phase 1 but controlled by a config flag so the app still starts on
machines without a GPU.

```groovy
// ONNX Runtime — CPU (always present)
implementation 'com.microsoft.onnxruntime:onnxruntime:1.20.0'

// ONNX Runtime — GPU execution providers (CUDA + DirectML).
// The same JAR exposes both; DirectML works on any DirectX-12 GPU (AMD/Intel/NVIDIA)
// without requiring CUDA drivers.
implementation 'com.microsoft.onnxruntime:onnxruntime_gpu:1.20.0'

// Apache Lucene — core + KNN vector search (HNSW)
implementation 'org.apache.lucene:lucene-core:9.12.0'
implementation 'org.apache.lucene:lucene-queryparser:9.12.0'
```

No version catalogue entry is needed — pin versions directly as shown.

### 1.2 Model files

Place ONNX model files under `src/main/resources/models/`. They are large binaries; add the
directory to `.gitignore` and document download instructions in the project README. Required
models:

| File | Source | Size |
|---|---|---|
| `retinaface_mbn025.onnx` | InsightFace buffalo_sc export | ~1.7 MB |
| `arcface_r50.onnx` | InsightFace buffalo_l export | ~166 MB |
| `clip_image_vit_b32.onnx` | `openai/clip` → ONNX export script | ~350 MB |
| `clip_text_vit_b32.onnx` | `openai/clip` → ONNX export script | ~250 MB |

At runtime the app copies models from the classpath to `~/.cimages/models/` on first launch
so the fat JAR remains self-contained.

### 1.3 New class: `OnnxModelRegistry`

**Package:** `com.github.curiousoddman.curious_images.ai`

Singleton Spring `@Component`. Loads and caches `OrtSession` instances. Sessions are
expensive to create; they must be created once and reused across all inference calls.

```java
@Component
public class OnnxModelRegistry implements DisposableBean {

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final AiConfig config;   // see §1.4

    public OrtSession getOrLoad(String modelKey, Path modelPath) {
        return sessions.computeIfAbsent(modelKey, k -> {
            try {
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(config.getIntraOpThreads());
                switch (config.getExecutionProvider()) {
                    case CUDA       -> opts.addCUDA(0);
                    case DIRECTML   -> opts.addDirectML(0);
                    case CPU        -> { /* default */ }
                }
                return env.createSession(modelPath.toString(), opts);
            } catch (OrtException e) {
                throw new RuntimeException("Failed to load ONNX model: " + modelPath, e);
            }
        });
    }

    @Override
    public void destroy() {
        sessions.values().forEach(s -> { try { s.close(); } catch (OrtException ignored) {} });
        env.close();
    }
}
```

### 1.4 New class: `AiConfig`

**Package:** `com.github.curiousoddman.curious_images.config`

Spring `@ConfigurationProperties(prefix = "ai")` class. Bind from `application.properties`.

```java
@ConfigurationProperties(prefix = "ai")
@Component
public class AiConfig {
    private ExecutionProvider executionProvider = ExecutionProvider.CPU;
    private int intraOpThreads = 4;
    private Path modelDir = Path.of(System.getProperty("user.home"), ".cimages", "models");
    private int batchSize = 8;

    public enum ExecutionProvider { CPU, CUDA, DIRECTML }
    // getters/setters
}
```

`application.properties` defaults:

```properties
ai.execution-provider=CPU
ai.intra-op-threads=4
ai.batch-size=8
```

### 1.5 New class: `ModelPaths`

**Package:** `com.github.curiousoddman.curious_images.ai`

Helper `@Component` that resolves model file paths and copies them from the classpath JAR to
the local model directory on first launch.

```java
@Component
public class ModelPaths {
    private final AiConfig config;

    public Path retinaFace()  { return resolve("retinaface_mbn025.onnx"); }
    public Path arcFace()     { return resolve("arcface_r50.onnx"); }
    public Path clipImage()   { return resolve("clip_image_vit_b32.onnx"); }
    public Path clipText()    { return resolve("clip_text_vit_b32.onnx"); }

    private Path resolve(String filename) {
        Path target = config.getModelDir().resolve(filename);
        if (!Files.exists(target)) {
            extractFromClasspath("/models/" + filename, target);
        }
        return target;
    }

    private void extractFromClasspath(String resource, Path target) {
        // Files.createDirectories + getClass().getResourceAsStream → Files.copy
    }
}
```

---

## Phase 2 — Face detection (RetinaFace)

### 2.1 New Flyway migration: `V006__face_schema.sql`

```sql
-- One row per detected face in a photo.
-- person_id is NULL until the face is assigned to a person (Phase 3).
CREATE TABLE face
(
    id           BIGSERIAL PRIMARY KEY,
    photo_id     BIGINT NOT NULL REFERENCES photo (id) ON DELETE CASCADE,
    person_id    BIGINT,                    -- FK added in V007 once PERSON table exists
    bbox_x       FLOAT  NOT NULL,           -- normalised [0,1] relative to image width
    bbox_y       FLOAT  NOT NULL,
    bbox_w       FLOAT  NOT NULL,
    bbox_h       FLOAT  NOT NULL,
    confidence   FLOAT  NOT NULL,
    landmark_json VARCHAR(512),             -- JSON array of 5 [x,y] pairs, normalised
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX idx_face_photo ON face (photo_id);
CREATE INDEX idx_face_person ON face (person_id);

-- One row per photo: tracks where each photo is in the AI processing pipeline.
-- Kept separate from PHOTO (same discipline as THUMBNAIL and PHOTO_HASH).
CREATE TABLE ai_processing_status
(
    photo_id          BIGINT PRIMARY KEY REFERENCES photo (id) ON DELETE CASCADE,
    face_detect_done  BOOLEAN NOT NULL DEFAULT FALSE,
    face_embed_done   BOOLEAN NOT NULL DEFAULT FALSE,
    clip_embed_done   BOOLEAN NOT NULL DEFAULT FALSE,
    lucene_index_done BOOLEAN NOT NULL DEFAULT FALSE,
    last_error        VARCHAR(1024),
    retry_count       SMALLINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMP
);
```

### 2.2 New class: `RetinaFaceDetector`

**Package:** `com.github.curiousoddman.curious_images.ai`

Wraps the RetinaFace ONNX model. Accepts a `BufferedImage`, returns a list of detected faces
with bounding boxes and 5-point landmarks.

```java
@Component
public class RetinaFaceDetector {
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.7f;

    private final OnnxModelRegistry registry;
    private final ModelPaths paths;

    /** Returns detected faces for a single image. */
    public List<DetectedFace> detect(BufferedImage image) throws OrtException {
        OrtSession session = registry.getOrLoad("retinaface", paths.retinaFace());

        float[][][][] input = preprocess(image);         // resize to 640×640, normalise
        OnnxTensor tensor = OnnxTensor.createTensor(session.getEnvironment(), input);

        try (OrtSession.Result result = session.run(Map.of("input", tensor))) {
            return parseOutput(result, image.getWidth(), image.getHeight());
        }
    }

    // preprocess: resize to INPUT_SIZE×INPUT_SIZE, BGR pixel order, mean-subtract
    // parseOutput: decode bounding boxes + landmarks, filter by CONFIDENCE_THRESHOLD,
    //              normalise coords to [0,1]

    public record DetectedFace(float x, float y, float w, float h,
                                float confidence, float[][] landmarks) {}
}
```

Preprocessing notes:
- Resize to 640×640 using `thumbnailator` (already on classpath).
- Pixel order: BGR (not RGB). Swap channels manually or via a `int[]` pixel scan.
- Normalise: subtract mean `[104, 117, 123]` per channel, no division.
- Output shape: RetinaFace MobileNet0.25 produces three output tensors (classifications,
  bounding boxes, landmarks). Parse according to the model's anchor grid.

### 2.3 New repository: `FaceRepository`

**Package:** `com.github.curiousoddman.curious_images.persistence`

Follow the same jOOQ hand-written pattern as `PhotoRepository`.

Methods needed:
- `insertQuery(photoId, x, y, w, h, confidence, landmarkJson, now)` → `Query` (batched)
- `findByPhotoId(photoId)` → `List<FaceRecord>`
- `findByPersonId(personId)` → `List<FaceRecord>`
- `deleteByPhotoId(ctx, photoId)` (transactional delete, same pattern as `ThumbnailRepository`)

### 2.4 New repository: `AiProcessingStatusRepository`

**Package:** `com.github.curiousoddman.curious_images.persistence`

Methods needed:
- `upsertQuery(photoId, now)` → `Query` — inserts a blank row (all flags false) the first time
- `markFaceDetectDoneQuery(photoId, now)` → `Query`
- `markFaceEmbedDoneQuery(photoId, now)` → `Query`
- `markClipEmbedDoneQuery(photoId, now)` → `Query`
- `markLuceneIndexDoneQuery(photoId, now)` → `Query`
- `markErrorQuery(photoId, error, now)` → `Query`
- `findPendingFaceDetect()` → `List<Long>` (photo IDs where `face_detect_done = false`)
- `findPendingClipEmbed()` → `List<Long>`

### 2.5 New background job: `AiPipelineJob`

**Package:** `com.github.curiousoddman.curious_images.domain.ai`

Extends `AbstractBackgroundJob`. Triggered by a new `RunAiPipelineEvent` (published by
`ImportService` at the end of a successful import, after it already publishes
`LibraryUpdatedEvent`).

Pipeline stages in order per photo:
1. Face detection → write `face` rows + mark `face_detect_done`
2. Face embedding → write embeddings + mark `face_embed_done` (Phase 3)
3. CLIP embedding → write embeddings + mark `clip_embed_done` (Phase 4)
4. Lucene index → commit to vector index + mark `lucene_index_done` (Phase 5)

Each stage queries `AiProcessingStatusRepository` for photos not yet at that stage, so the
job is fully resumable: restarting after a crash picks up exactly where it left off.

Structure (abbreviated):

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPipelineJob extends AbstractBackgroundJob {

    @EventListener
    public void onRunAiPipeline(RunAiPipelineEvent event) {
        if (!tryStart()) return;
        new Thread(this::run, "ai-pipeline").start();
    }

    private void run() {
        publishStarted("Starting AI pipeline...");
        try {
            runFaceDetection();
            runFaceEmbedding();       // Phase 3
            runClipEmbedding();       // Phase 4
            runLuceneIndexing();      // Phase 5
            publishEnded("AI pipeline complete");
        } catch (Exception e) {
            publishFailed(e);
        } finally {
            finish();
        }
    }

    private void runFaceDetection() {
        List<Long> photoIds = aiStatusRepo.findPendingFaceDetect();
        publishInProgress("Detecting faces...", 0, photoIds.size());
        List<Query> buffer = new ArrayList<>();
        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) { flush(buffer); publishInterrupted("Interrupted"); return; }
            long photoId = photoIds.get(i);
            try {
                PhotoRecord photo = photoRepo.findById(photoId);
                BufferedImage img = loadImage(photo);
                List<DetectedFace> faces = retinaFaceDetector.detect(img);
                LocalDateTime now = timeProvider.now();
                for (DetectedFace face : faces) {
                    buffer.add(faceRepo.insertQuery(photoId, face.x(), face.y(), face.w(), face.h(),
                            face.confidence(), toLandmarkJson(face.landmarks()), now));
                }
                buffer.add(aiStatusRepo.markFaceDetectDoneQuery(photoId, now));
            } catch (Exception e) {
                log.warn("Face detection failed for photo {}", photoId, e);
                buffer.add(aiStatusRepo.markErrorQuery(photoId, e.getMessage(), timeProvider.now()));
            }
            if (buffer.size() >= BATCH_SIZE) flush(buffer);
            publishProgress("Detecting faces...", i + 1, photoIds.size(), String.valueOf(photoId), false);
        }
        flush(buffer);
    }
}
```

---

## Phase 3 — Face embedding + person clustering (ArcFace)

### 3.1 New Flyway migration: `V007__person_schema.sql`

```sql
CREATE TABLE person
(
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(256),              -- user-assigned; NULL until named
    cover_face_id BIGINT,                   -- FK to face(id), nullable
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP
);

-- Add the FK from face to person now that person exists
ALTER TABLE face ADD CONSTRAINT fk_face_person
    FOREIGN KEY (person_id) REFERENCES person (id);

-- ArcFace 512-dim float32 embedding, stored as raw bytes (512 * 4 = 2048 bytes).
-- Kept separate from FACE to avoid widening the row used in queries that don't need it.
CREATE TABLE face_embedding
(
    face_id    BIGINT PRIMARY KEY REFERENCES face (id) ON DELETE CASCADE,
    embedding  BINARY(2048) NOT NULL,       -- float32[512], L2-normalised
    model_ver  VARCHAR(32)  NOT NULL        -- e.g. "arcface_r50"
);
```

### 3.2 New class: `ArcFaceEncoder`

**Package:** `com.github.curiousoddman.curious_images.ai`

Accepts an aligned 112×112 face crop (`BufferedImage`), returns a 512-dim L2-normalised
`float[]`.

```java
@Component
public class ArcFaceEncoder {
    private final OnnxModelRegistry registry;
    private final ModelPaths paths;

    public float[] encode(BufferedImage alignedFace) throws OrtException {
        OrtSession session = registry.getOrLoad("arcface", paths.arcFace());
        float[][][][] input = preprocess(alignedFace);   // 112×112, RGB, normalise to [-1,1]
        OnnxTensor tensor = OnnxTensor.createTensor(session.getEnvironment(), input);
        try (OrtSession.Result result = session.run(Map.of("input.1", tensor))) {
            float[] embedding = (float[]) result.get(0).getValue();
            return l2Normalize(embedding);
        }
    }

    private float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }
}
```

Preprocessing notes:
- Align the face crop using the 5-point landmarks from RetinaFace before calling this.
- The reference alignment target for ArcFace is the InsightFace 112×112 template.
- Pixel normalisation: `(pixel / 127.5f) - 1.0f` per channel, RGB order.

### 3.3 New class: `FaceAligner`

**Package:** `com.github.curiousoddman.curious_images.ai`

Applies a 2D similarity transform from source landmarks to the ArcFace 112×112 reference
landmarks. Implement as pure Java using `AffineTransform` + `Graphics2D.drawImage` (avoids
an OpenCV dependency).

```java
@Component
public class FaceAligner {
    // ArcFace reference 5-point landmarks (112×112 space)
    private static final float[][] REFERENCE = {
        {38.29f, 51.69f}, {73.53f, 51.50f},
        {56.02f, 71.74f},
        {41.55f, 92.37f}, {70.73f, 92.20f}
    };

    /**
     * Crops and aligns a face from {@code source} using detected landmarks.
     * {@code landmarks} is float[5][2] in pixel coordinates (not normalised).
     */
    public BufferedImage align(BufferedImage source, float[][] landmarks) {
        AffineTransform t = estimateSimilarityTransform(landmarks, REFERENCE);
        BufferedImage out = new BufferedImage(112, 112, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(source, t, null);
        g.dispose();
        return out;
    }

    private AffineTransform estimateSimilarityTransform(float[][] src, float[][] dst) {
        // Compute scale, rotation, and translation via least-squares similarity fit.
        // See Umeyama algorithm (simplified 2D version):
        // https://web.stanford.edu/class/cs273/refs/umeyama.pdf
        // Implement here — ~50 lines of matrix arithmetic with no external libraries.
    }
}
```

### 3.4 New repository: `FaceEmbeddingRepository`

**Package:** `com.github.curiousoddman.curious_images.persistence`

Methods:
- `upsertQuery(faceId, embeddingBytes, modelVer)` → `Query`
- `findAll()` → `List<FaceEmbeddingRecord>` (used by clustering)
- `findByFaceIds(Collection<Long>)` → `Map<Long, FaceEmbeddingRecord>`

Serialise `float[]` to `byte[]` as little-endian float32: use `ByteBuffer.allocate(512*4).order(LITTLE_ENDIAN)`.

### 3.5 New service: `PersonClusteringService`

**Package:** `com.github.curiousoddman.curious_images.domain.ai`

Runs after all face embeddings are generated. Groups faces into person candidates using
greedy cosine-similarity clustering.

```java
@Component
public class PersonClusteringService {
    private static final float SIMILARITY_THRESHOLD = 0.4f;
    private static final int MIN_FACES_PER_PERSON = 3;

    public void cluster() {
        List<FaceEmbeddingRecord> embeddings = faceEmbeddingRepo.findAll();
        // 1. Build union-find structure
        // 2. For each pair (i, j): if cosine(ei, ej) > threshold → union(i, j)
        //    (For large sets: use approximate KNN from Lucene face index instead of O(n²))
        // 3. Groups with size >= MIN_FACES_PER_PERSON → create PERSON rows
        // 4. Assign face.person_id for each group member
        // 5. Publish PersonsUpdatedEvent so UI can refresh
    }
}
```

For libraries with > 10,000 faces, switch the O(n²) pairwise loop for a Lucene KNN query
per face (see Phase 5 — the face vector index is available by then).

### 3.6 New repository: `PersonRepository`

Methods:
- `insert(name, coverFaceId, now)` → `long` (returns new ID immediately, same as `PhotoRepository.insert`)
- `findAll()` → `List<PersonRecord>`
- `findById(id)` → `Optional<PersonRecord>`
- `updateName(ctx, id, name, now)` → `Query`
- `updateCoverFace(id, faceId, now)` → `Query`

---

## Phase 4 — CLIP image embeddings

### 4.1 New Flyway migration: `V008__clip_schema.sql`

```sql
-- CLIP 512-dim image embedding, one row per photo.
CREATE TABLE clip_embedding
(
    photo_id   BIGINT PRIMARY KEY REFERENCES photo (id) ON DELETE CASCADE,
    embedding  BINARY(2048) NOT NULL,       -- float32[512], L2-normalised
    model_ver  VARCHAR(32)  NOT NULL        -- e.g. "clip_vit_b32"
);
```

### 4.2 New class: `ClipImageEncoder`

**Package:** `com.github.curiousoddman.curious_images.ai`

```java
@Component
public class ClipImageEncoder {
    // CLIP ViT-B/32 normalisation constants
    private static final float[] MEAN = {0.48145466f, 0.4578275f,  0.40821073f};
    private static final float[] STD  = {0.26862954f, 0.26130258f, 0.27577711f};

    public float[] encode(BufferedImage image) throws OrtException {
        OrtSession session = registry.getOrLoad("clip_image", paths.clipImage());
        float[][][][] input = preprocess(image);   // resize 224×224, centre-crop, normalise
        OnnxTensor tensor = OnnxTensor.createTensor(session.getEnvironment(), input);
        try (OrtSession.Result result = session.run(Map.of("input", tensor))) {
            return l2Normalize((float[]) result.get(0).getValue());
        }
    }
    // preprocess: resize shortest edge to 224, centre-crop to 224×224,
    //             per-channel: (pixel/255 - mean) / std
}
```

### 4.3 New class: `ClipTextEncoder`

**Package:** `com.github.curiousoddman.curious_images.ai`

The text encoder is only called at query time (not during batch import), so it does not need
to be memory-resident. The `OnnxModelRegistry` will load it lazily on first search.

```java
@Component
public class ClipTextEncoder {
    public float[] encode(String text) throws OrtException {
        OrtSession session = registry.getOrLoad("clip_text", paths.clipText());
        // 1. Tokenise text using a bundled CLIP BPE tokeniser.
        //    Bundle the vocab file (clip-vit-b-32-vocab.json + merges.txt) in resources.
        //    Use a pure-Java BPE implementation (~200 lines) — no Python dependency.
        // 2. Pad/truncate to 77 tokens.
        // 3. Run session, L2-normalise output.
        long[][] tokens = clipTokenizer.tokenize(text);
        OnnxTensor tensor = OnnxTensor.createTensor(session.getEnvironment(), tokens);
        try (OrtSession.Result result = session.run(Map.of("input", tensor))) {
            return l2Normalize((float[]) result.get(0).getValue());
        }
    }
}
```

**Important:** Bundle the CLIP BPE tokeniser vocabulary files in
`src/main/resources/clip-tokenizer/`. Implement `ClipTokenizer` as a pure-Java BPE tokeniser
(no Python, no external subprocess). The vocab JSON and merges file can be downloaded from
the `openai/CLIP` GitHub repository.

### 4.4 New repository: `ClipEmbeddingRepository`

Methods:
- `upsertQuery(photoId, embeddingBytes, modelVer)` → `Query`
- `findByPhotoId(photoId)` → `Optional<ClipEmbeddingRecord>`
- `findAll()` → `List<ClipEmbeddingRecord>` (for album building)

---

## Phase 5 — Apache Lucene vector index

### 5.1 New class: `LuceneConfig`

**Package:** `com.github.curiousoddman.curious_images.config`

Manages lifecycle of Lucene `Directory`, `IndexWriter`, and `DirectoryReader`. Stored in
`~/.cimages/index/`.

```java
@Configuration
public class LuceneConfig {
    private static final Path INDEX_DIR = 
        Path.of(System.getProperty("user.home"), ".cimages", "index");

    @Bean(destroyMethod = "close")
    public IndexWriter clipIndexWriter() throws IOException {
        Directory dir = MMapDirectory.open(INDEX_DIR.resolve("clip"));
        IndexWriterConfig cfg = new IndexWriterConfig()
                .setRAMBufferSizeMB(64)
                .setCommitOnClose(true);
        return new IndexWriter(dir, cfg);
    }

    @Bean(destroyMethod = "close")
    public IndexWriter faceIndexWriter() throws IOException {
        Directory dir = MMapDirectory.open(INDEX_DIR.resolve("face"));
        return new IndexWriter(dir, new IndexWriterConfig().setCommitOnClose(true));
    }

    @Bean
    public SearcherManager clipSearcherManager(IndexWriter clipIndexWriter) throws IOException {
        return new SearcherManager(clipIndexWriter, null);
    }

    @Bean
    public SearcherManager faceSearcherManager(IndexWriter faceIndexWriter) throws IOException {
        return new SearcherManager(faceIndexWriter, null);
    }
}
```

### 5.2 New class: `ClipVectorIndex`

**Package:** `com.github.curiousoddman.curious_images.index`

```java
@Component
public class ClipVectorIndex {
    private static final int DIMS = 512;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;

    /** Adds or replaces the CLIP embedding for a photo. */
    public void upsert(long photoId, float[] embedding) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("photo_id", String.valueOf(photoId), Field.Store.YES));
        doc.add(new KnnFloatVectorField("clip_vec", embedding, VectorSimilarityFunction.DOT_PRODUCT));
        writer.updateDocument(new Term("photo_id", String.valueOf(photoId)), doc);
    }

    /** Commits buffered writes. Call after each batch. */
    public void commit() throws IOException {
        writer.commit();
        searcherManager.maybeRefresh();
    }

    /** Returns up to {@code k} photo IDs ordered by cosine similarity to {@code queryVec}. */
    public List<Long> search(float[] queryVec, int k) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs hits = searcher.search(new KnnFloatVectorQuery("clip_vec", queryVec, k), k);
            return Arrays.stream(hits.scoreDocs)
                    .map(sd -> {
                        try {
                            return Long.parseLong(searcher.storedFields()
                                    .document(sd.doc).get("photo_id"));
                        } catch (IOException e) { throw new UncheckedIOException(e); }
                    })
                    .collect(Collectors.toList());
        } finally {
            searcherManager.release(searcher);
        }
    }
}
```

### 5.3 New class: `FaceVectorIndex`

**Package:** `com.github.curiousoddman.curious_images.index`

Same structure as `ClipVectorIndex`. Documents contain:
- `face_id` (stored string field)
- `person_id` (stored string field, `-1` if unassigned)
- `face_vec` (KNN float vector, 512 dims)

Additional method:
```java
/** Returns up to k face IDs with optional person_id filter. */
public List<Long> searchFaces(float[] queryVec, int k, Long personIdFilter) throws IOException { ... }
```

### 5.4 Wiring into `AiPipelineJob`

The `runLuceneIndexing()` stage in `AiPipelineJob` (Phase 2):
1. Loads `ClipEmbeddingRecord` for photos with `lucene_index_done = false`.
2. Calls `clipVectorIndex.upsert(photoId, embedding)` for each.
3. Loads `FaceEmbeddingRecord` for faces belonging to those photos.
4. Calls `faceVectorIndex.upsert(faceId, personId, embedding)` for each.
5. Calls `clipVectorIndex.commit()` and `faceVectorIndex.commit()` after each batch.
6. Marks `lucene_index_done = true` in `ai_processing_status`.

---

## Phase 6 — Semantic search & similar-photo discovery

### 6.1 New class: `SearchService`

**Package:** `com.github.curiousoddman.curious_images.domain.search`

```java
@Component
public class SearchService {

    /**
     * Encodes {@code query} with CLIP text encoder and returns matching photo IDs,
     * ordered by descending cosine similarity.
     */
    public List<Long> semanticSearch(String query, int topK) throws Exception {
        float[] textEmbedding = clipTextEncoder.encode(query);
        return clipVectorIndex.search(textEmbedding, topK);
    }

    /**
     * Returns photo IDs visually similar to {@code photoId}.
     * The query photo itself is excluded from results.
     */
    public List<Long> similarPhotos(long photoId, int topK) throws Exception {
        ClipEmbeddingRecord rec = clipEmbeddingRepo.findByPhotoId(photoId)
                .orElseThrow(() -> new IllegalStateException("No CLIP embedding for photo " + photoId));
        float[] embedding = toFloatArray(rec.getEmbedding());
        List<Long> results = clipVectorIndex.search(embedding, topK + 1);
        results.remove(photoId);
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * Combined person + semantic search: finds photos of {@code personId} that also
     * match {@code semanticQuery}. Intersects results from both dimensions.
     */
    public List<Long> combinedSearch(long personId, String semanticQuery, int topK) throws Exception {
        Set<Long> personPhotos = faceRepo.findByPersonId(personId).stream()
                .map(FaceRecord::getPhotoId).collect(Collectors.toSet());
        float[] textEmbedding = clipTextEncoder.encode(semanticQuery);
        List<Long> semanticResults = clipVectorIndex.search(textEmbedding, topK * 5);
        return semanticResults.stream()
                .filter(personPhotos::contains)
                .limit(topK)
                .collect(Collectors.toList());
    }
}
```

### 6.2 UI integration

Add a `SearchBar` component to `library.fxml`:
- `TextField` for query input (bound to `LibraryController`).
- A toggle button to switch between "Semantic search" and "Browse" modes.
- On Enter / button press: fire a `SearchRequestedEvent` carrying the query string.
- `LibraryController` listens, calls `SearchService.semanticSearch`, populates `photoGridPane`
  with results (same `populatePhotoGrid` method used for folder/timeline views).

Add a "Similar photos" context menu item to the photo cell in `createPhotoCell`:
```java
MenuItem similar = new MenuItem("Find similar photos");
similar.setOnAction(e -> {
    List<Long> ids = searchService.similarPhotos(photo.getId(), 50);
    runOnFxThread(() -> populatePhotoGrid(loadPhotos(ids), loadThumbnails(ids)));
});
```

---

## Phase 7 — Automatic album generation

### 7.1 New Flyway migration: `V009__album_schema.sql`

```sql
CREATE TABLE album
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(256) NOT NULL,
    type           VARCHAR(20)  NOT NULL,   -- PERSON | EVENT | LOCATION | SIMILARITY | MANUAL
    cover_photo_id BIGINT REFERENCES photo (id),
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP,
    meta_json      TEXT                     -- type-specific: date range, GPS bounds, person IDs
);

CREATE TABLE album_photo
(
    album_id   BIGINT NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    photo_id   BIGINT NOT NULL REFERENCES photo (id) ON DELETE CASCADE,
    sort_order INT NOT NULL DEFAULT 0,
    added_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (album_id, photo_id)
);
CREATE INDEX idx_album_photo_album ON album_photo (album_id);
CREATE INDEX idx_album_photo_photo ON album_photo (photo_id);
```

### 7.2 New repositories

**`AlbumRepository`** — methods:
- `insert(name, type, coverPhotoId, metaJson, now)` → `long`
- `deleteByType(ctx, type)` — used before rebuilding auto-generated album types
- `findAll()` → `List<AlbumRecord>`
- `findById(id)` → `Optional<AlbumRecord>`

**`AlbumPhotoRepository`** — methods:
- `insertQuery(albumId, photoId, sortOrder, now)` → `Query`
- `findPhotoIdsByAlbumId(albumId)` → `List<Long>`

### 7.3 New service: `AlbumGenerationService`

**Package:** `com.github.curiousoddman.curious_images.domain.album`

One `@Component` with four private methods, each rebuilding one album type. Triggered by a
`RegenerateAlbumsEvent` published at the end of `AiPipelineJob`.

#### Person albums

```java
private void buildPersonAlbums(DSLContext ctx) {
    albumRepo.deleteByType(ctx, "PERSON");
    for (PersonRecord person : personRepo.findAll()) {
        List<Long> photoIds = faceRepo.findByPersonId(person.getId()).stream()
                .map(FaceRecord::getPhotoId).distinct().collect(Collectors.toList());
        if (photoIds.isEmpty()) continue;
        String name = person.getName() != null ? person.getName() : "Unknown person #" + person.getId();
        long albumId = albumRepo.insert(name, "PERSON", photoIds.get(0), null, now);
        List<Query> buf = new ArrayList<>();
        for (int i = 0; i < photoIds.size(); i++)
            buf.add(albumPhotoRepo.insertQuery(albumId, photoIds.get(i), i, now));
        flush(buf);
    }
}
```

#### Event albums

```java
private void buildEventAlbums(DSLContext ctx) {
    // 1. Load all photos with non-null capture_date, order by capture_date ASC.
    // 2. Split into events: a new event starts whenever the gap between consecutive
    //    photos exceeds EVENT_GAP_HOURS (default 6, make it configurable).
    // 3. Discard events with fewer than MIN_EVENT_SIZE photos (default 5).
    // 4. Name: "YYYY-MM-DD" of the first photo in the event.
    // 5. Cover photo: the photo in the event with the highest sharpness score.
    //    Sharpness = variance of Laplacian on the thumbnail (compute once, cheap).
    albumRepo.deleteByType(ctx, "EVENT");
    // ... build and persist ...
}
```

#### Location albums

```java
private void buildLocationAlbums(DSLContext ctx) {
    // 1. Load all photos with non-null GPS coords (add gps_lat, gps_lon columns in
    //    a separate migration V010 — see §7.4).
    // 2. Group by reverse-geocoded city string using an offline geocoder.
    //    Recommended: bundle GeoNames allCountries.txt (500 MB) or use
    //    a trimmed city-level subset (~20 MB) for city-name lookup only.
    //    Simple approach: nearest-neighbour lookup on (lat, lon) → city name.
    // 3. Create one album per city with >= MIN_LOCATION_SIZE photos (default 3).
    albumRepo.deleteByType(ctx, "LOCATION");
    // ... build and persist ...
}
```

#### Visual similarity albums

```java
private void buildSimilarityAlbums(DSLContext ctx) {
    // 1. Load all CLIP embeddings from clip_embedding table.
    // 2. Run k-means clustering: k = (int) Math.sqrt(totalPhotos / 2.0)
    //    Implement k-means in pure Java (~80 lines) — no external library needed.
    // 3. For each cluster with >= MIN_CLUSTER_SIZE photos (default 10) and
    //    intra-cluster average cosine similarity > 0.6:
    //    a. Name the cluster by zero-shot CLIP label matching against a fixed
    //       vocabulary list (sunset, food, landscape, people, architecture, etc.)
    //       — compute cosine(cluster_centroid, encode(label)) for each label.
    //    b. Create a SIMILARITY album.
    albumRepo.deleteByType(ctx, "SIMILARITY");
    // ... build and persist ...
}
```

### 7.4 New Flyway migration: `V010__photo_gps.sql`

```sql
-- GPS columns needed for location album generation.
ALTER TABLE photo ADD COLUMN gps_lat DOUBLE;
ALTER TABLE photo ADD COLUMN gps_lon DOUBLE;
ALTER TABLE photo ADD COLUMN gps_altitude DOUBLE;
```

Also update `PhotoMetadataExtractor` to extract GPS tags using `metadata-extractor` (it
already supports `GpsDirectory`), and update `PhotoRepository.insert` and
`PhotoRepository.updateMetadataQuery` to write these columns.

---

## New event types to add

All events follow the existing pattern (plain Spring application events, not queued):

| Event class | Published by | Consumed by |
|---|---|---|
| `RunAiPipelineEvent` | `ImportService` (after `LibraryUpdatedEvent`) | `AiPipelineJob` |
| `AiPipelineCompleteEvent` | `AiPipelineJob` | `AlbumGenerationService`, `LibraryController` |
| `RegenerateAlbumsEvent` | `AiPipelineJob` | `AlbumGenerationService` |
| `PersonsUpdatedEvent` | `PersonClusteringService` | `LibraryController` (refresh person tree) |

---

## New package structure

```
com.github.curiousoddman.curious_images/
├── ai/
│   ├── OnnxModelRegistry.java
│   ├── ModelPaths.java
│   ├── RetinaFaceDetector.java
│   ├── ArcFaceEncoder.java
│   ├── FaceAligner.java
│   ├── ClipImageEncoder.java
│   ├── ClipTextEncoder.java
│   └── ClipTokenizer.java
├── config/
│   ├── AiConfig.java          (new)
│   └── LuceneConfig.java      (new)
├── domain/
│   ├── ai/
│   │   ├── AiPipelineJob.java
│   │   └── PersonClusteringService.java
│   ├── album/
│   │   └── AlbumGenerationService.java
│   └── search/
│       └── SearchService.java
├── index/
│   ├── ClipVectorIndex.java
│   └── FaceVectorIndex.java
└── persistence/
    ├── FaceRepository.java
    ├── FaceEmbeddingRepository.java
    ├── PersonRepository.java
    ├── ClipEmbeddingRepository.java
    ├── AiProcessingStatusRepository.java
    ├── AlbumRepository.java
    └── AlbumPhotoRepository.java
```

---

## Summary of Flyway migrations to create

| File | Contents |
|---|---|
| `V006__face_schema.sql` | `face`, `ai_processing_status` tables |
| `V007__person_schema.sql` | `person`, `face_embedding` tables; FK from `face` to `person` |
| `V008__clip_schema.sql` | `clip_embedding` table |
| `V009__album_schema.sql` | `album`, `album_photo` tables |
| `V010__photo_gps.sql` | GPS columns on `photo` |

---

## Implementation order (recommended)

1. **Phase 1** — ONNX Runtime in `build.gradle`, `OnnxModelRegistry`, `AiConfig`, `ModelPaths`. Verify model files load without error.
2. **Phase 2** — `V006`, `RetinaFaceDetector`, `FaceRepository`, `AiProcessingStatusRepository`, `AiPipelineJob` (face detection stage only). Verify faces are detected and persisted.
3. **Phase 3** — `V007`, `ArcFaceEncoder`, `FaceAligner`, `FaceEmbeddingRepository`, `PersonRepository`, `PersonClusteringService`. Verify embeddings are stored and persons are clustered.
4. **Phase 4** — `V008`, `ClipImageEncoder`, `ClipTextEncoder`, `ClipTokenizer`, `ClipEmbeddingRepository`. Verify CLIP embeddings are stored.
5. **Phase 5** — `LuceneConfig`, `ClipVectorIndex`, `FaceVectorIndex`. Wire into `AiPipelineJob` indexing stage. Verify KNN search returns sensible results.
6. **Phase 6** — `SearchService`, search bar UI in `library.fxml` + `LibraryController`, similar-photos context menu.
7. **Phase 7** — `V009`, `V010`, `AlbumRepository`, `AlbumPhotoRepository`, `AlbumGenerationService`. Wire album tree into `LibraryController`.

---

## Memory and performance notes

- All four ONNX models loaded simultaneously: ~855 MB RAM (CPU). Load models lazily — face
  models only when the AI pipeline runs, text encoder only when a search query is issued.
- Lucene HNSW index: ~4 KB per vector document. 100,000 photos with 2 faces each ≈ 1.2 GB
  index. Use `MMapDirectory` so the OS page cache handles the working set.
- Batch size (default 8, configurable via `ai.batch-size`): tune upward for GPU, downward if
  RAM is constrained.
- Use `ZGC` for the JVM GC (`-XX:+UseZGC`) to avoid long pauses during large batch inference.
  Add to `launch-app.bat` in the `deploy` task.
- The `clip_text_vit_b32.onnx` session may be evicted from `OnnxModelRegistry` between
  searches to reclaim ~250 MB RAM; reload it on next query (< 1 second on SSD).