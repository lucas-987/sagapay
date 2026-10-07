CREATE TABLE fraud_cases (
    case_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id       TEXT NOT NULL UNIQUE,
    sender_id         TEXT NOT NULL,
    amount            NUMERIC(20,4) NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'EUR',
    score             NUMERIC(5,4) NOT NULL,
    reasons           TEXT[] NOT NULL,
    status            TEXT NOT NULL DEFAULT 'OPEN',
    transfer_outcome  TEXT,
    velocity_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    opened_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at        TIMESTAMPTZ
);
CREATE INDEX idx_fraud_cases_status ON fraud_cases(status, opened_at);
CREATE INDEX idx_fraud_cases_sender ON fraud_cases(sender_id, opened_at DESC);
