-- CLIP 512-dim image embedding, one row per photo.
CREATE TABLE clip_embedding
(
    photo_id  BIGINT PRIMARY KEY REFERENCES photo (id) ON DELETE CASCADE,
    embedding BINARY(2048) NOT NULL,        -- float32[512], L2-normalised
    model_ver VARCHAR(32)  NOT NULL         -- e.g. "clip_vit_b32"
);
