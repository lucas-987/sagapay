-- A key stays here until an operator repairs its row and deletes the entry: the application role
-- has no DELETE, because publishing around a row that failed would break the order of the key.
CREATE TABLE outbox_quarantine (
    aggregate_id   UUID PRIMARY KEY,
    outbox_id      UUID NOT NULL REFERENCES outbox(id),
    reason         TEXT NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON outbox_quarantine TO ${fraudAppUsername};
