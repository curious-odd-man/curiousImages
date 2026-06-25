-- One row per detected face in a photo.
-- person_id is NULL until the face is assigned to a person (Phase 3).
CREATE TABLE face
(
    id                      BIGSERIAL PRIMARY KEY,
    photo_id                BIGINT    NOT NULL REFERENCES photo (id) ON DELETE CASCADE,
    person_id               BIGINT,             -- FK added in V007 once PERSON table exists
    bbox_x                  FLOAT     NOT NULL, -- normalised [0,1] relative to image width
    bbox_y                  FLOAT     NOT NULL,
    bbox_w                  FLOAT     NOT NULL,
    bbox_h                  FLOAT     NOT NULL,
    confidence              FLOAT     NOT NULL,
    landmark_json           VARCHAR(512),       -- JSON array of 5 [x,y] pairs, normalised
    created_at              TIMESTAMP NOT NULL,
    thumbnail_absolute_path VARCHAR(2048)
);
CREATE INDEX idx_face_photo ON face (photo_id);
CREATE INDEX idx_face_person ON face (person_id);

-- One row per photo: tracks where each photo is in the AI processing pipeline.
-- Kept separate from PHOTO (same discipline as THUMBNAIL and PHOTO_HASH).
CREATE TABLE ai_processing_status
(
    photo_id          BIGINT PRIMARY KEY REFERENCES photo (id) ON DELETE CASCADE,
    face_detect_done  BOOLEAN  NOT NULL DEFAULT FALSE,
    face_embed_done   BOOLEAN  NOT NULL DEFAULT FALSE,
    clip_embed_done   BOOLEAN  NOT NULL DEFAULT FALSE,
    lucene_index_done BOOLEAN  NOT NULL DEFAULT FALSE,
    last_error        VARCHAR(1024),
    retry_count       SMALLINT NOT NULL DEFAULT 0,
    updated_at        TIMESTAMP
);
