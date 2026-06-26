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

