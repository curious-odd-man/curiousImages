CREATE TABLE album
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(256) NOT NULL,
    type           VARCHAR(20)  NOT NULL,    -- PERSON | EVENT | LOCATION | SIMILARITY | MANUAL
    cover_photo_id BIGINT REFERENCES photo (id),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP,
    meta_json      TEXT                      -- type-specific: date range, GPS bounds, person IDs
);
CREATE INDEX idx_album_type ON album (type);

CREATE TABLE album_photo
(
    album_id   BIGINT NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    photo_id   BIGINT NOT NULL REFERENCES photo (id) ON DELETE CASCADE,
    sort_order INT    NOT NULL DEFAULT 0,
    added_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (album_id, photo_id)
);
CREATE INDEX idx_album_photo_album ON album_photo (album_id);
CREATE INDEX idx_album_photo_photo ON album_photo (photo_id);
