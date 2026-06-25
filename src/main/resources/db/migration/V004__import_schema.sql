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

CREATE TABLE photo
(
    id                  BIGSERIAL PRIMARY KEY,
    folder_id           BIGINT REFERENCES folder (id),
    absolute_path       VARCHAR(2048) UNIQUE,
    filename            VARCHAR(512),
    extension           VARCHAR(10),
    file_size           BIGINT,
    image_width         INT,
    image_height        INT,
    capture_date        TIMESTAMP,
    capture_date_source VARCHAR(20), -- EXIF_ORIGINAL | EXIF_DIGITIZED | FILESYSTEM
    imported_at         TIMESTAMP,
    last_seen_at        TIMESTAMP,
    orientation         INT NOT NULL DEFAULT 0,
    camera_make         VARCHAR(100),
    camera_model        VARCHAR(100),
    lens_model          VARCHAR(150),
    exif_extra          JSON,
    gps_lat             DOUBLE,
    gps_lon             DOUBLE,
    gps_altitude        DOUBLE
);

CREATE INDEX idx_photo_folder ON photo (folder_id);
CREATE INDEX idx_photo_capture_date ON photo (capture_date);

-- Deliberately no image bytes stored here — only the cache path. See implementation plan §10.
CREATE TABLE thumbnail
(
    photo_id     BIGINT PRIMARY KEY REFERENCES photo (id),
    cache_path   VARCHAR(2048), -- relative to the configured thumbnail cache root
    width        INT,
    height       INT,
    generated_at TIMESTAMP
);

