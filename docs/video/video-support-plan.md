# Video Support — Implementation Plan (v2)

## Status

| Phase | Status |
|---|---|
| 1. Schema migration + jOOQ regen + app compiles/runs (photos-only behavior unchanged) | 🟡 In progress |
| 2. Video import + browse (extension filtering, metadata, thumbnails, plain grid) | ⬜ Not started |
| 3. Hover-preview playback | ⬜ Not started |
| 4. Duplicate detection (file-hash, `media_hash`) | ⬜ Not started |
| 5. AI pipeline (frame sampling + face/CLIP + clustering) | ⬜ Not started |
| 6. Albums + unified search, `MediaItem` abstraction | ⬜ Not started |

### Phase 1 sub-checklist
- [x] `V004__import_schema.sql` — `media`/`photo`/`video` split, `thumbnail` → `media_id`
- [x] `V005__duplicate_schema.sql` — `photo_hash` → `media_hash` (+ `hash_type`), `duplicate_group`/`duplicate_group_member` → `content_hash`/`media_id`
- [x] `V006__face_schema.sql` — `face.photo_id` → `media_id`, added `frame_offset_ms`
- [x] `V008__clip_schema.sql` — `clip_embedding` → `media_id` + surrogate `id` PK (needed once a single media item can have >1 embedding), added `frame_offset_ms`
- [x] `V009__album_schema.sql` — `album.cover_photo_id` → `cover_media_id`, `album_photo` → `album_media`
- [x] `V013__tag_data.sql` — dropped the now-redundant `ai_tag_done` ALTER (lives on `media` from the start), `photo_tag` → `media_tag`
- [ ] Regenerate jOOQ sources (`./gradlew jooqCodegen` or a full build) — **must be run locally**, no Maven Central access in the environment these edits were made in
- [ ] Split `PhotoRepository` into `MediaRepository` (shared columns/AI-status flags) + thin `PhotoRepository`/new `VideoRepository`
- [ ] Rename/update `AlbumPhotoRepository` → `AlbumMediaRepository`, add a `MediaTagRepository` (was `PhotoTagRepository`), update `FaceRepository`, `ClipEmbeddingRepository`/`ClipVectorIndex`, `DuplicateGroupRepository`, `ThumbnailGenerator`/`ThumbnailCachePaths`, `PixelHasher`, `DuplicateDetectionJob` for the `media_id` rename
- [ ] Update the ~28 files that reference `PhotoRecord` (grid/slideshow/duplicates/person/album UI + `AiPipelineJob`, `AlbumGenerationJob`, `ImportJob`, `ThumbnailGenerationJob`) — full list captured during scanning, see PR/commit notes
- [ ] Full local build to confirm everything still compiles and photos-only behavior is unchanged

This last stretch (jOOQ regen + repository/UI layer rename) is a genuinely large, mechanical refactor — roughly 30 files — and is much safer to do against real compiler errors than blind. Recommended path: run `./gradlew jooqCodegen build` locally now that the migrations are updated, and share the resulting compile errors back for a precise fix pass, rather than have the Java layer rewritten unverified.

Supersedes the earlier draft. Key decisions locked in during discussion:

| Decision | Choice |
|---|---|
| v1 scope | Full parity with photos: faces, CLIP search, dedupe, albums |
| Data model | **Option B** — shared `media` parent table; `photo` and `video` become thin subtype tables |
| Migration strategy | No release yet → **edit existing Flyway migrations in place**, no data migration needed |
| Accepted formats | MP4/MOV, H.264 video + AAC audio only (what JavaFX `MediaPlayer` can natively decode) |
| Hover preview (grid) | Play the actual file, muted + looping, directly via JavaFX `MediaPlayer` |
| Full-screen playback | Hand off to the OS's default video player |
| AI embeddings | Sample a few frames per video (start/middle/end or every N seconds); each sampled frame gets a face/CLIP embedding, same as a photo would |
| Duplicate detection | Exact-duplicate only (file hash + size) — no perceptual/frame hashing for v1 |
| Import options | Same as photos — user chooses copy-into-library vs. register-in-place |
| Video-decode/metadata dependency | FFmpeg (via a JVM wrapper, e.g. JavaCV), bundled/downloaded like the ONNX models |

---

## 1. Data model

### 1.1 Why a shared table (not two independent tables)

Nearly every "cross-cutting" table in the current schema keys off `photo_id`: `thumbnail`, `photo_hash`, `duplicate_group_member`, `face`, `clip_embedding`, `album_photo`, `photo_tag`. If `video` were a fully independent table with its own `id` sequence, a video and a photo could collide on `id` — so these shared tables can't just start accepting either id in the same column without breaking the FK constraint (an FK can only point at one table).

The clean fix: introduce a thin `media` parent table that owns the identity (`id`, path, shared metadata, AI-pipeline status flags), and make `photo` / `video` subtype tables whose primary key **is** the `media.id` (`id BIGINT PRIMARY KEY REFERENCES media(id)`). Every table that's conceptually "per-item" (thumbnail, dedupe hash, faces, embeddings, tags, album membership) now references `media(id)` once — one join, one FK, no dual-column branching, and no id collisions.

### 1.2 New shape

```sql
CREATE TABLE media
(
    id                   BIGSERIAL PRIMARY KEY,
    media_type           VARCHAR(10)  NOT NULL,     -- PHOTO | VIDEO
    folder_id            BIGINT REFERENCES folder (id),
    absolute_path        VARCHAR(2048) UNIQUE,
    filename             VARCHAR(512),
    extension            VARCHAR(10),
    file_size            BIGINT,
    capture_date         TIMESTAMP,
    capture_date_source  VARCHAR(20),               -- EXIF_ORIGINAL | EXIF_DIGITIZED | CONTAINER_METADATA | FILESYSTEM
    imported_at          TIMESTAMP,
    last_seen_at         TIMESTAMP,
    camera_make          VARCHAR(100),
    camera_model         VARCHAR(100),
    gps_lat              DOUBLE,
    gps_lon              DOUBLE,
    gps_altitude         DOUBLE,
    ai_face_detect_done  BOOLEAN  NOT NULL DEFAULT FALSE,
    ai_face_embed_done   BOOLEAN  NOT NULL DEFAULT FALSE,
    ai_clip_embed_done   BOOLEAN  NOT NULL DEFAULT FALSE,
    ai_tag_done          BOOLEAN  NOT NULL DEFAULT FALSE,
    ai_lucene_index_done BOOLEAN  NOT NULL DEFAULT FALSE,
    ai_last_error        VARCHAR(1024),
    ai_retry_count       SMALLINT NOT NULL DEFAULT 0,
    ai_updated_at        TIMESTAMP
);
CREATE INDEX idx_media_folder ON media (folder_id);
CREATE INDEX idx_media_capture_date ON media (capture_date);
CREATE INDEX idx_media_type ON media (media_type);

-- Photo-specific columns only
CREATE TABLE photo
(
    id           BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    image_width  INT,
    image_height INT,
    orientation  INT NOT NULL DEFAULT 0,
    lens_model   VARCHAR(150),
    exif_extra   JSON
);

-- Video-specific columns only
CREATE TABLE video
(
    id          BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    width       INT,
    height      INT,
    duration_ms BIGINT,
    codec       VARCHAR(50),
    frame_rate  FLOAT,
    rotation    INT NOT NULL DEFAULT 0
);
```

Rationale for what moved to `media` vs. stayed on the subtype table: `capture_date`, `gps_*`, `camera_make/model` are used generically by timeline/location albums and search filters that iterate all media regardless of type — those live on `media`. `image_width/height` vs. video `width/height/duration/codec/frame_rate` are genuinely type-specific (different extraction pipeline: EXIF vs. container probing), so they stay on the subtype tables.

### 1.3 Every shared table that changes, and how

| Table | Migration file | Change |
|---|---|---|
| `thumbnail` | `V004__import_schema.sql` | `photo_id` → `media_id`, FK now → `media(id)` |
| `photo_hash` | `V005__duplicate_schema.sql` | Rename table → `media_hash`; `photo_id` → `media_id`. For video rows this stores a plain file hash instead of a decoded-pixel hash — add a `hash_type VARCHAR(10)` column (`PIXEL` \| `FILE`) so the dedupe job knows which comparison rule produced it, and comparisons never cross types. |
| `duplicate_group_member` | `V005__duplicate_schema.sql` | `photo_id` → `media_id` |
| `face` | `V006__face_schema.sql` | `photo_id` → `media_id`; add nullable `frame_offset_ms BIGINT` (NULL for photos, sampled-frame timestamp for videos) |
| `clip_embedding` | `V008__clip_schema.sql` | `photo_id` → `media_id`; add nullable `frame_offset_ms BIGINT`. Also switches from `photo_id` as the primary key to a surrogate `id BIGSERIAL` (with a `UNIQUE (media_id, frame_offset_ms)`), since a video now legitimately has more than one embedding row |
| `album` | `V009__album_schema.sql` | `cover_photo_id` → `cover_media_id`, FK now → `media(id)` |
| `album_photo` | `V009__album_schema.sql` | Rename table → `album_media`; `photo_id` → `media_id` |
| `photo_tag` | `V013__tag_data.sql` | Rename table → `media_tag`; `photo_id` → `media_id` |
| `photo.ai_tag_done` | `V013__tag_data.sql` | This `ALTER TABLE photo ADD ai_tag_done` line is deleted entirely — the column now lives on `media` from the start (§1.2), since V004 is being edited directly and there's no data to preserve |

Tables that **don't** change:
- `photo_preview` (`V010`) — this is EXIF-embedded-thumbnail-bytes, a genuinely photo-only concept (video containers don't have this). Stays keyed to `photo(id)`, which still works fine since `photo.id` is a `media.id` under the hood.
- `person`, `cluster`, `face_embedding` (`V007`, `V011`) — already keyed off `face`/`person`, never touched `photo` directly. No change needed.
- `duplicate_group`, `duplicate_job` (`V005`, `V012`) — reference `duplicate_group_member`, not `photo` directly. No change needed.
- `tag_embedding` (`V013`) — reference data (categories/tags + their CLIP embeddings), not tied to photo or video at all. No change.
- `import_root`, `folder` — already generic containers. No change.

### 1.4 jOOQ / repository fallout
- Regenerate jOOQ sources against the edited schema — every `PHOTO.xxx` reference to a moved/renamed column needs updating.
- `PhotoRepository` splits conceptually into `MediaRepository` (shared: insert base row, folder queries, AI-status flags) + thin `PhotoRepository`/`VideoRepository` for the type-specific columns.
- `ThumbnailCachePaths`/`ThumbnailGenerator`, `FaceRepository`, `ClipEmbeddingRepository`, `AlbumPhotoRepository` (→ `AlbumMediaRepository`), `PhotoTagRepository` (→ `MediaTagRepository`) all shift from `photo_id` to `media_id` parameters — mechanical but touches a fair number of call sites.

---

## 2. Import & scanning
- Extend `ImportJob.SUPPORTED_EXTENSIONS` with `mp4, mov, m4v`.
- On discovery, branch by extension: image extensions → existing `PhotoMetadataExtractor` path, inserting a `media` row + `photo` row; video extensions → new `VideoMetadataExtractor`, inserting a `media` row + `video` row.
- `VideoMetadataExtractor` probes duration, codec, frame rate, rotation, container creation-date/GPS atoms via FFmpeg/FFprobe.
- **Codec validation at import**: reject (with a clear "unsupported codec" message in the existing skipped-files report) any video that isn't H.264/AAC — this is what keeps the hover-preview promise honest, since JavaFX can only play what it can decode.
- Copy-in-place vs. register-new-root: unchanged, videos flow through the same `AddFilesController`/`ImportJob` choice photos already have.

## 3. Thumbnails
- New `VideoThumbnailGenerator`: extract one frame (e.g. 1s in, or 10% of duration) via FFmpeg, decode to a `BufferedImage`, then reuse the existing `ThumbnailGenerator` resize/cache logic unchanged.
- `thumbnail` table (now keyed by `media_id`) works for both without modification beyond the rename.
- Grid cell gets a small play-icon overlay to distinguish video tiles.

## 4. Playback
- **Hover preview**: new grid cell variant (`VideoCellController`) with a `MediaView`/`MediaPlayer` bound to the original file. `onMouseEntered` → play muted+looped; `onMouseExited` → stop, reset to thumbnail frame. Cap concurrent players to the currently-hovered cell only (dispose on scroll-away) — `MediaPlayer` instances are a real native resource, not free.
- **Full-screen**: reuse the existing "open" action, but for a video item launch the OS default player (`Desktop.getDesktop().open(file)` or platform equivalent) instead of the in-app slideshow viewer.
- Because we're restricting accepted formats to what JavaFX can already play, no proxy-transcode step is needed — the original file is always hover-playable.

## 5. AI pipeline (faces + CLIP)
- Extend the AI pipeline job to branch on `media_type`. For video: sample N frames (fixed heuristic to start — e.g. frames at 10%/50%/90% of duration, capped by a max sample count) via FFmpeg frame extraction, then feed each sampled frame through the **existing** `RetinaFaceDetector` / `ArcFaceEncoder` / `ClipImageEncoder` — no new AI models, just new call sites and a loop over sampled frames instead of a single decode.
- Each resulting `face`/`clip_embedding` row records `frame_offset_ms`, so results can jump back to "this moment in the video."
- `PersonClusteringService` needs to accept face rows regardless of whether they came from a photo or a video frame — since `face` is now keyed by `media_id` uniformly, this should mostly just work once the repository layer is updated; still worth a deliberate test pass since clustering logic wasn't written with "same media, multiple embeddings" in mind.
- Settings screen gets a new "Video" section (frame-sampling count/interval), live-applied the same way as the AI performance settings added earlier.

## 6. Duplicate detection
- Exact-duplicate only: SHA-256 of file bytes + file size, stored in `media_hash` with `hash_type = FILE` for videos (vs. `PIXEL` for photos' decoded-pixel hash). `DuplicateDetectionJob` groups within the same `hash_type` (mirrors the existing "never group across extensions" rule) so a photo and a video are never considered duplicates of each other, and re-encoded videos with a different byte layout are correctly treated as distinct (accepted limitation of exact-hash matching, consistent with what was agreed).

## 7. Search
- `ClipTextEncoder` natural-language search now queries `clip_embedding` rows regardless of media type (single table post-migration, so this is mostly free). Multiple frame-hits from the same video get collapsed into one search result, showing the best-matching frame as the result's thumbnail.

## 8. UI touch points
- Grid: new cell type (play icon, hover-loop playback).
- Full-screen: video items hand off to the OS player.
- Person/Album pages: render mixed photo+video grids.
- Settings: new "Video" section (frame sampling params), alongside the existing AI performance section.

## 9. `MediaItem` abstraction (UI/domain layer)
Independent of the DB refactor, the Java domain/UI layer still benefits from a small `MediaItem` sealed interface (`PhotoItem` / `VideoItem`) so grid, album, search, and person-page code doesn't need parallel branches everywhere. With the shared `media` table this is a more natural fit than it would've been with two fully independent tables — `MediaRepository` can return a `MediaItem` directly by checking `media_type` and joining the right subtype table.

## 10. Phased rollout
1. **Schema migration** (§1) + regenerate jOOQ + get the app compiling/running again with photos-only behavior unchanged (this alone is a meaningful chunk of work given the fan-out in §1.3).
2. **Video import + browse**: extension filtering, metadata extraction, thumbnail generation, plain grid display (no hover playback yet, no AI).
3. **Hover-preview playback**.
4. **Duplicate detection** (file-hash based, `media_hash`).
5. **AI pipeline**: frame sampling + face/CLIP indexing + person-clustering integration.
6. **Albums + unified search** tying videos into existing album/search UI, `MediaItem` abstraction lands here if not already introduced earlier.

## Open items still worth a decision before/while coding
- **JavaCV/FFmpeg wrapper choice** — pick a specific library at implementation time (licensing/size tradeoffs vary); flagging so it's a visible decision, not buried in code.
- **Frame-sampling defaults** — proposed a simple heuristic above (10/50/90%, capped count); exposed as a Settings-screen knob from day one rather than a hardcoded guess.
- **`hash_type` on `media_hash`** — confirms photos and videos are never cross-compared for duplicates; worth double-checking this matches your expectation before implementation.
