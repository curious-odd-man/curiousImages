CREATE TABLE import_root
(
    id              BIGSERIAL PRIMARY KEY,
    path            VARCHAR(1024) UNIQUE,
    created_at      TIMESTAMP,
    last_scanned_at TIMESTAMP
);

-- The import root itself is represented as a FOLDER row too (relative_path = '',
-- parent_folder_id = NULL), so every PHOTO can simply reference a folder_id without
-- a special case for "files directly in the root".
CREATE TABLE folder
(
    id               BIGSERIAL PRIMARY KEY,
    import_root_id   BIGINT REFERENCES import_root (id),
    parent_folder_id BIGINT REFERENCES folder (id),
    relative_path    VARCHAR(1024),
    name             VARCHAR(255),
    UNIQUE (import_root_id, relative_path)
);

-- ── Shared media identity ────────────────────────────────────────────────
-- Every imported item (photo or video) gets exactly one MEDIA row: this owns the file
-- identity (path/filename/size), the metadata that's meaningful across both types
-- (capture date, GPS, camera make/model), and the AI-pipeline status flags. PHOTO/VIDEO
-- are thin subtype tables keyed 1:1 on MEDIA.id, holding only what's genuinely
-- type-specific (image dimensions/orientation/EXIF vs. video duration/codec/frame rate) —
-- see docs/video/video-support-plan.md §1 for the full rationale. Every other table that
-- used to reference PHOTO directly (thumbnail, dedupe hash, face, clip_embedding, album
-- membership, tags) now references MEDIA instead, so a video can flow through the exact
-- same pipelines a photo does.
CREATE TABLE media
(
    id                   BIGSERIAL PRIMARY KEY,
    media_type           VARCHAR(10) NOT NULL, -- PHOTO | VIDEO
    folder_id            BIGINT REFERENCES folder (id),
    absolute_path        VARCHAR(2048) UNIQUE,
    filename             VARCHAR(512),
    extension            VARCHAR(10),
    file_size            BIGINT,
    width                INT,
    height               INT,
    capture_date         TIMESTAMP,
    capture_date_source  VARCHAR(20),          -- EXIF_ORIGINAL | EXIF_DIGITIZED | CONTAINER_METADATA | FILESYSTEM
    imported_at          TIMESTAMP,
    last_seen_at         TIMESTAMP,
    camera_make          VARCHAR(100),
    camera_model         VARCHAR(100),
    gps_lat              DOUBLE,
    gps_lon              DOUBLE,
    gps_altitude         DOUBLE,
    ai_face_detect_done  BOOLEAN     NOT NULL DEFAULT FALSE,
    ai_face_embed_done   BOOLEAN     NOT NULL DEFAULT FALSE,
    ai_clip_embed_done   BOOLEAN     NOT NULL DEFAULT FALSE,
    ai_tag_done          BOOLEAN     NOT NULL DEFAULT FALSE,
    ai_lucene_index_done BOOLEAN     NOT NULL DEFAULT FALSE,
    ai_last_error        VARCHAR(1024),
    ai_retry_count       SMALLINT    NOT NULL DEFAULT 0,
    ai_updated_at        TIMESTAMP
);

CREATE INDEX idx_media_folder ON media (folder_id);
CREATE INDEX idx_media_capture_date ON media (capture_date);
CREATE INDEX idx_media_type ON media (media_type);

-- Photo-only columns. id IS media.id (shared primary key / identifying relationship).
CREATE TABLE photo
(
    id          BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    orientation INT NOT NULL DEFAULT 0,
    lens_model  VARCHAR(150),
    exif_extra  JSON
);

-- Video-only columns. id IS media.id (shared primary key / identifying relationship).
CREATE TABLE video
(
    id          BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    duration_ms BIGINT,
    codec       VARCHAR(50),
    frame_rate  FLOAT,
    rotation    INT NOT NULL DEFAULT 0
);

CREATE VIEW media_photo AS
SELECT *
FROM photo
         join media using(id);

CREATE VIEW media_video AS
SELECT *
FROM video
         join media using(id);

-- Deliberately no image bytes stored here — only the cache path. See implementation plan §10.
CREATE TABLE thumbnail
(
    media_id     BIGINT PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    cache_path   VARCHAR(2048), -- relative to the configured thumbnail cache root
    width        INT,
    height       INT,
    generated_at TIMESTAMP
);

