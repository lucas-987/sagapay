CREATE TABLE processed_events (
    event_id     TEXT PRIMARY KEY,
    consumer     TEXT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
