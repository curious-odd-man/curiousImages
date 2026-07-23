-- One row per detected face in a photo, or in a sampled frame of a video.
-- person_id is NULL until the face is assigned to a person (Phase 3).
CREATE TABLE face
(
    id                      BIGSERIAL PRIMARY KEY,
    media_id                BIGINT    NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    frame_offset_ms         BIGINT,             -- NULL for photos; sampled-frame timestamp for videos
    person_id               BIGINT,             -- FK added in V007 once PERSON table exists
    bbox_x                  FLOAT     NOT NULL, -- normalised [0,1] relative to image width
    bbox_y                  FLOAT     NOT NULL,
    bbox_w                  FLOAT     NOT NULL,
    bbox_h                  FLOAT     NOT NULL,
    confidence              FLOAT     NOT NULL,
    landmark_left_eye_x     FLOAT,
    landmark_left_eye_y     FLOAT,
    landmark_right_eye_x    FLOAT,
    landmark_right_eye_y    FLOAT,
    landmark_nose_x         FLOAT,
    landmark_nose_y         FLOAT,
    landmark_left_mouth_x   FLOAT,
    landmark_left_mouth_y   FLOAT,
    landmark_right_mouth_x  FLOAT,
    landmark_right_mouth_y  FLOAT,
    created_at              TIMESTAMP NOT NULL,
    thumbnail_absolute_path VARCHAR(2048)
);
CREATE INDEX idx_face_media ON face (media_id);
CREATE INDEX idx_face_person ON face (person_id);

