-- A column rather than a field read at publication: a row without its key now fails the business
-- transaction that writes it, instead of becoming a row nothing can publish.
ALTER TABLE outbox ADD COLUMN partition_key TEXT;
UPDATE outbox SET partition_key = COALESCE(NULLIF(payload->>'senderId', ''), aggregate_id::text);
ALTER TABLE outbox ALTER COLUMN partition_key SET NOT NULL;
ALTER TABLE outbox ADD CONSTRAINT chk_outbox_partition_key CHECK (partition_key <> '');

-- A key stays here until an operator repairs its row and deletes the entry: the application role
-- has no DELETE, because publishing around a row that failed would break the order of the key.
CREATE TABLE outbox_quarantine (
    partition_key  TEXT PRIMARY KEY,
    outbox_id      UUID NOT NULL REFERENCES outbox(id),
    reason         TEXT NOT NULL,
    quarantined_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON outbox_quarantine TO ${orchestratorAppUsername};
