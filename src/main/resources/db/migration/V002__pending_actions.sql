CREATE TABLE pending_action
(
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(255) NOT NULL,
    payload         VARBINARY,
    status          VARCHAR(30)  NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    last_error      TEXT
);
