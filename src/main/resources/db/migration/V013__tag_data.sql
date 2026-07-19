ALTER TABLE photo
    ADD COLUMN ai_tag_done BOOLEAN DEFAULT FALSE;

CREATE TABLE tag_embedding
(
    id        BIGSERIAL PRIMARY KEY,
    category  VARCHAR(255),
    tag       VARCHAR(255),
    embedding BINARY(2048),
    model_ver VARCHAR(32)
);

CREATE TABLE photo_tag
(
    tag_id     BIGINT NOT NULL REFERENCES tag_embedding (id) ON DELETE CASCADE,
    photo_id   BIGINT NOT NULL REFERENCES photo (id) ON DELETE CASCADE,
    tag_source VARCHAR(20), -- AI or USER
    confidence FLOAT,
    PRIMARY KEY (tag_id, photo_id)
);