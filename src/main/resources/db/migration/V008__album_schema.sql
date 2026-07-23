CREATE TABLE album
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(256) NOT NULL,
    type           VARCHAR(20)  NOT NULL,    -- PERSON | EVENT | LOCATION | SIMILARITY | MANUAL
    cover_media_id BIGINT REFERENCES media (id),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP,
    meta_json      TEXT                      -- type-specific: date range, GPS bounds, person IDs
);
CREATE INDEX idx_album_type ON album (type);

CREATE TABLE album_media
(
    album_id   BIGINT NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    media_id   BIGINT NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    sort_order INT    NOT NULL DEFAULT 0,
    added_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (album_id, media_id)
);
CREATE INDEX idx_album_media_album ON album_media (album_id);
CREATE INDEX idx_album_media_media ON album_media (media_id);
