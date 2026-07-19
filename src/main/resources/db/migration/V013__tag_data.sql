CREATE TABLE tag
(
    id        BIGSERIAL PRIMARY KEY,
    tag       VARCHAR(255),
    embedding BINARY(2048),
    model_ver VARCHAR(32)
);
