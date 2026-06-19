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

CREATE TABLE IF NOT EXISTS user_preferences
(
    pref_key   VARCHAR(255) NOT NULL PRIMARY KEY,
    pref_value VARCHAR(255)
);

MERGE INTO user_preferences (pref_key, pref_value) KEY (pref_key) VALUES ('window.x', '100'),
                                                                         ('window.y', '100'),
                                                                         ('window.width', '1920'),
                                                                         ('window.height', '1080'),
                                                                         ('window.maximized', 'false'),
                                                                         ('ui.split.width', '0.26');