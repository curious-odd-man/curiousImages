-- Per-photo pixel hash, used by duplicate detection to decide whether a photo needs
-- (re)hashing on a given run. Mirrors THUMBNAIL's 1:1-with-PHOTO shape rather than adding
-- columns to PHOTO directly, so PHOTO stays import-pipeline-only.
--
-- "Skip unchanged on rerun" works by comparing hashed_file_size to the current
-- PHOTO.file_size: if they match, the file hasn't changed since it was last hashed (same
-- cheap signal ImportService already uses to skip unchanged files on rescan), so the
-- existing pixel_hash is reused instead of re-decoding the file.
--
-- For CR2: pixel_hash is computed from the decoded embedded preview (same source the
-- thumbnail pipeline uses), never from a full RAW render — consistent with "do not perform
-- full RAW rendering" elsewhere in the spec. This means CR2 duplicate detection is really
-- "preview identical", not "full raw decode identical".
CREATE TABLE photo_hash
(
    photo_id         BIGINT PRIMARY KEY REFERENCES photo (id),
    pixel_hash       VARCHAR(64), -- SHA-256 hex of decoded pixel bytes (or CR2 preview)
    hashed_file_size BIGINT,      -- PHOTO.file_size at hash time; mismatch => stale, rehash
    hashed_at        TIMESTAMP
);
CREATE INDEX idx_photo_hash_pixel_hash ON photo_hash (pixel_hash);

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
-- (extension, pixel_hash) is unique because "compares only within the same file type" means
-- a hash collision across extensions (e.g. matching JPEG and CR2 previews) must never be
-- grouped together.
CREATE TABLE duplicate_group
(
    id               BIGSERIAL PRIMARY KEY,
    duplicate_job_id BIGINT REFERENCES duplicate_job (id),
    extension        VARCHAR(10),
    pixel_hash       VARCHAR(64),
    created_at       TIMESTAMP,
    UNIQUE (extension, pixel_hash)
);
CREATE INDEX idx_duplicate_group_job ON duplicate_group (duplicate_job_id);

CREATE TABLE duplicate_group_member
(
    id                 BIGSERIAL PRIMARY KEY,
    duplicate_group_id BIGINT REFERENCES duplicate_group (id),
    photo_id           BIGINT REFERENCES photo (id),
    UNIQUE (duplicate_group_id, photo_id)
);
CREATE INDEX idx_duplicate_group_member_group ON duplicate_group_member (duplicate_group_id);
CREATE INDEX idx_duplicate_group_member_photo ON duplicate_group_member (photo_id);
