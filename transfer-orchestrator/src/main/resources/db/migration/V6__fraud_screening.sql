ALTER TABLE saga_steps ADD COLUMN deadline TIMESTAMPTZ;
CREATE INDEX idx_saga_steps_retry_deadline ON saga_steps(deadline) WHERE outcome = 'RETRY';

ALTER TABLE transfers ADD COLUMN fraud_score NUMERIC(5,4);
ALTER TABLE transfers ADD COLUMN fraud_reasons TEXT[];

CREATE TABLE processed_events (
    event_id     UUID NOT NULL PRIMARY KEY,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON processed_events TO ${orchestratorAppUsername};
