-- Per-media content hash, used by duplicate detection to decide whether an item needs
-- (re)hashing on a given run. Mirrors THUMBNAIL's 1:1-with-MEDIA shape rather than adding
-- columns to MEDIA directly, so MEDIA stays import-pipeline-only.
--
-- "Skip unchanged on rerun" works by comparing hashed_file_size to the current
-- MEDIA.file_size: if they match, the file hasn't changed since it was last hashed (same
-- cheap signal ImportService already uses to skip unchanged files on rescan), so the
-- existing content_hash is reused instead of re-hashing the file.
--
-- hash_type distinguishes how content_hash was computed, since photos and videos are hashed
-- differently and must never be compared across types:
--   PIXEL - decoded pixel bytes (photos). For CR2, computed from the decoded embedded
--           preview (same source the thumbnail pipeline uses), never a full RAW render —
--           consistent with "do not perform full RAW rendering" elsewhere in the spec, so
--           CR2 duplicate detection is really "preview identical", not "full raw decode
--           identical".
--   FILE  - plain SHA-256 of the raw file bytes (videos). Exact-duplicate only for v1: no
--           perceptual/frame-based matching, so a re-encoded/trimmed copy of the same video
--           will not be detected as a duplicate. See video-support-plan.md §6.
CREATE TABLE media_hash
(
    media_id         BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    hash_type        VARCHAR(10), -- PIXEL | FILE
    content_hash     VARCHAR(64), -- SHA-256 hex
    hashed_file_size BIGINT,      -- MEDIA.file_size at hash time; mismatch => stale, rehash
    hashed_at        TIMESTAMP
);
CREATE INDEX idx_media_hash_content_hash ON media_hash (content_hash);

-- One row per duplicate-detection run. Exists so DUPLICATE_GROUP rows can be scoped to "which
-- run produced this", and so an interrupted/failed run is visible/diagnosable (mirrors the
-- progress/cancellation/resumable requirements) without needing a full job queue like
-- PENDING_ACTION.
CREATE TABLE duplicate_job
(
    id              BIGSERIAL PRIMARY KEY,
    status          VARCHAR(20), -- RUNNING | COMPLETED | INTERRUPTED | FAILED
    started_at      TIMESTAMP,
    ended_at        TIMESTAMP,
    total_count     INT,         -- photos in scope for this run
    processed_count INT DEFAULT 0,
    group_count     INT,         -- populated when COMPLETED
    error_message   VARCHAR(2048)
);

-- Current duplicate groups. Each completed run REPLACES the previous run's groups (old
-- DUPLICATE_GROUP/DUPLICATE_GROUP_MEMBER rows for prior duplicate_job_id values are deleted
-- once a new run completes successfully) — the Duplicates View always reflects the latest
-- completed run, rather than accumulating stale groups across reruns. An interrupted/failed
-- run leaves the previous run's groups untouched.
--
-- (extension, content_hash) is unique because "compares only within the same file type" means
-- a hash collision across extensions (e.g. matching JPEG and CR2 previews) must never be
-- grouped together. Note: PIXEL-hashed and FILE-hashed rows never collide here in practice
-- since extensions already differ between media and video formats, but the dedupe job still
-- scopes comparisons within the same media_hash.hash_type as a explicit safety rule (see
-- video-support-plan.md §6) so a future extension overlap can't silently cross-match.
CREATE TABLE duplicate_group
(
    id               BIGSERIAL PRIMARY KEY,
    duplicate_job_id BIGINT REFERENCES duplicate_job (id),
    extension        VARCHAR(10),
    content_hash     VARCHAR(64),
    created_at       TIMESTAMP,
    accepted         BOOLEAN,
    UNIQUE (extension, content_hash)
);
CREATE INDEX idx_duplicate_group_job ON duplicate_group (duplicate_job_id);

CREATE TABLE duplicate_group_member
(
    id                 BIGSERIAL PRIMARY KEY,
    duplicate_group_id BIGINT REFERENCES duplicate_group (id),
    media_id           BIGINT REFERENCES media (id),
    UNIQUE (duplicate_group_id, media_id)
);
CREATE INDEX idx_duplicate_group_member_group ON duplicate_group_member (duplicate_group_id);
CREATE INDEX idx_duplicate_group_member_media ON duplicate_group_member (media_id);
