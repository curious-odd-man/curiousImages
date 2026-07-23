-- CLIP 512-dim image embedding. One row per media, or one row per sampled frame for a video
-- (frame_offset_ms distinguishes them) — see video-support-plan.md §5.
CREATE TABLE clip_embedding
(
    id              BIGSERIAL PRIMARY KEY,
    media_id        BIGINT       NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    frame_offset_ms BIGINT,                    -- NULL for photos; sampled-frame timestamp for videos
    embedding       BINARY(2048) NOT NULL,     -- float32[512], L2-normalised
    model_ver       VARCHAR(32)  NOT NULL,     -- e.g. "clip_vit_b32"
    UNIQUE (media_id, frame_offset_ms)
);
CREATE INDEX idx_clip_embedding_media ON clip_embedding (media_id);
