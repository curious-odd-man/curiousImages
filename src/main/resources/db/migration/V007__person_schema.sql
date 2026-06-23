CREATE TABLE person
(
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(256),              -- user-assigned; NULL until named
    cover_face_id BIGINT,                   -- FK to face(id), nullable
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP
);

-- Add the FK from face to person now that person exists
ALTER TABLE face
    ADD CONSTRAINT fk_face_person FOREIGN KEY (person_id) REFERENCES person (id);

-- ArcFace 512-dim float32 embedding, stored as raw bytes (512 * 4 = 2048 bytes).
-- Kept separate from FACE to avoid widening the row used in queries that don't need it.
CREATE TABLE face_embedding
(
    face_id   BIGINT PRIMARY KEY REFERENCES face (id) ON DELETE CASCADE,
    embedding BINARY(2048) NOT NULL,        -- float32[512], L2-normalised
    model_ver VARCHAR(32)  NOT NULL         -- e.g. "arcface_r50"
);
