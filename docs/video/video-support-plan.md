# Video Support — Implementation Plan (v2)

## Status

| Phase                                                                                 | Status           |
|---------------------------------------------------------------------------------------|------------------|
| 1. Schema migration + jOOQ regen + app compiles/runs (photos-only behavior unchanged) | 🟢 Completed~~~~ |
| 2. Video import + browse (extension filtering, metadata, thumbnails, plain grid)      | ⬜ Not started    |
| 3. Hover-preview playback                                                             | ⬜ Not started    |
| 4. Duplicate detection (file-hash, `media_hash`)                                      | ⬜ Not started    |
| 5. AI pipeline (frame sampling + face/CLIP + clustering)                              | ⬜ Not started    |
| 6. Albums + unified search, `MediaItem` abstraction                                   | ⬜ Not started    |

### Decisions

Supersedes the earlier draft. Key decisions locked in during discussion:

| Decision                         | Choice                                                                                                                                    |
|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| v1 scope                         | Full parity with photos: faces, CLIP search, dedupe, albums                                                                               |
| Data model                       | **Option B** — shared `media` parent table; `photo` and `video` become thin subtype tables                                                |
| Migration strategy               | No release yet → **edit existing Flyway migrations in place**, no data migration needed                                                   |
| Accepted formats                 | MP4/MOV, H.264 video + AAC audio only (what JavaFX `MediaPlayer` can natively decode)                                                     |
| Hover preview (grid)             | Play the actual file, muted + looping, directly via JavaFX `MediaPlayer`                                                                  |
| Full-screen playback             | Hand off to the OS's default video player                                                                                                 |
| AI embeddings                    | Sample a few frames per video (start/middle/end or every N seconds); each sampled frame gets a face/CLIP embedding, same as a photo would |
| Duplicate detection              | Exact-duplicate only (file hash + size) — no perceptual/frame hashing for v1                                                              |
| Import options                   | Same as photos — user chooses copy-into-library vs. register-in-place                                                                     |
| Video-decode/metadata dependency | FFmpeg (via a JVM wrapper, e.g. JavaCV), bundled/downloaded like the ONNX models                                                          |

## 2. Import & scanning

- Extend `ImportJob.SUPPORTED_EXTENSIONS` with `mp4, mov, m4v`.
- On discovery, branch by extension: image extensions → existing `PhotoMetadataExtractor` path, inserting a `media`
  row + `photo` row; video extensions → new `VideoMetadataExtractor`, inserting a `media` row + `video` row.
- `VideoMetadataExtractor` probes duration, codec, frame rate, rotation, container creation-date/GPS atoms via
  FFmpeg/FFprobe.
- **Codec validation at import**: reject (with a clear "unsupported codec" message in the existing skipped-files report)
  any video that isn't H.264/AAC — this is what keeps the hover-preview promise honest, since JavaFX can only play what
  it can decode.
- Copy-in-place vs. register-new-root: unchanged, videos flow through the same `AddFilesController`/`ImportJob` choice
  photos already have.

## 3. Thumbnails

- New `VideoThumbnailGenerator`: extract one frame (e.g. 1s in, or 10% of duration) via FFmpeg, decode to a
  `BufferedImage`, then reuse the existing `ThumbnailGenerator` resize/cache logic unchanged.
- `thumbnail` table (now keyed by `media_id`) works for both without modification beyond the rename.
- Grid cell gets a small play-icon overlay to distinguish video tiles.

## 4. Playback

- **Hover preview**: new grid cell variant (`VideoCellController`) with a `MediaView`/`MediaPlayer` bound to the
  original file. `onMouseEntered` → play muted+looped; `onMouseExited` → stop, reset to thumbnail frame. Cap concurrent
  players to the currently-hovered cell only (dispose on scroll-away) — `MediaPlayer` instances are a real native
  resource, not free.
- **Full-screen**: reuse the existing "open" action, but for a video item launch the OS default player (
  `Desktop.getDesktop().open(file)` or platform equivalent) instead of the in-app slideshow viewer.
- Because we're restricting accepted formats to what JavaFX can already play, no proxy-transcode step is needed — the
  original file is always hover-playable.

## 5. AI pipeline (faces + CLIP)

- Extend the AI pipeline job to branch on `media_type`. For video: sample N frames (fixed heuristic to start — e.g.
  frames at 10%/50%/90% of duration, capped by a max sample count) via FFmpeg frame extraction, then feed each sampled
  frame through the **existing** `RetinaFaceDetector` / `ArcFaceEncoder` / `ClipImageEncoder` — no new AI models, just
  new call sites and a loop over sampled frames instead of a single decode.
- Each resulting `face`/`clip_embedding` row records `frame_offset_ms`, so results can jump back to "this moment in the
  video."
- `PersonClusteringService` needs to accept face rows regardless of whether they came from a photo or a video frame —
  since `face` is now keyed by `media_id` uniformly, this should mostly just work once the repository layer is updated;
  still worth a deliberate test pass since clustering logic wasn't written with "same media, multiple embeddings" in
  mind.
- Settings screen gets a new "Video" section (frame-sampling count/interval), live-applied the same way as the AI
  performance settings added earlier.

## 6. Duplicate detection

- Exact-duplicate only: SHA-256 of file bytes + file size, stored in `media_hash` with `hash_type = FILE` for videos (
  vs. `PIXEL` for photos' decoded-pixel hash). `DuplicateDetectionJob` groups within the same `hash_type` (mirrors the
  existing "never group across extensions" rule) so a photo and a video are never considered duplicates of each other,
  and re-encoded videos with a different byte layout are correctly treated as distinct (accepted limitation of
  exact-hash matching, consistent with what was agreed).

## 7. Search

- `ClipTextEncoder` natural-language search now queries `clip_embedding` rows regardless of media type (single table
  post-migration, so this is mostly free). Multiple frame-hits from the same video get collapsed into one search result,
  showing the best-matching frame as the result's thumbnail.

## 8. UI touch points

- Grid: new cell type (play icon, hover-loop playback).
- Full-screen: video items hand off to the OS player.
- Person/Album pages: render mixed photo+video grids.
- Settings: new "Video" section (frame sampling params), alongside the existing AI performance section.

## 9. `MediaItem` abstraction (UI/domain layer)

Independent of the DB refactor, the Java domain/UI layer still benefits from a small `MediaItem` sealed interface (
`PhotoItem` / `VideoItem`) so grid, album, search, and person-page code doesn't need parallel branches everywhere. With
the shared `media` table this is a more natural fit than it would've been with two fully independent tables —
`MediaRepository` can return a `MediaItem` directly by checking `media_type` and joining the right subtype table.

## 10. Phased rollout

1. **Schema migration** (§1) + regenerate jOOQ + get the app compiling/running again with photos-only behavior
   unchanged (this alone is a meaningful chunk of work given the fan-out in §1.3).
2. **Video import + browse**: extension filtering, metadata extraction, thumbnail generation, plain grid display (no
   hover playback yet, no AI).
3. **Hover-preview playback**.
4. **Duplicate detection** (file-hash based, `media_hash`).
5. **AI pipeline**: frame sampling + face/CLIP indexing + person-clustering integration.
6. **Albums + unified search** tying videos into existing album/search UI, `MediaItem` abstraction lands here if not
   already introduced earlier.

## Open items still worth a decision before/while coding

- **JavaCV/FFmpeg wrapper choice** — pick a specific library at implementation time (licensing/size tradeoffs vary);
  flagging so it's a visible decision, not buried in code.
- **Frame-sampling defaults** — proposed a simple heuristic above (10/50/90%, capped count); exposed as a
  Settings-screen knob from day one rather than a hardcoded guess.
- **`hash_type` on `media_hash`** — confirms photos and videos are never cross-compared for duplicates; worth
  double-checking this matches your expectation before implementation.
