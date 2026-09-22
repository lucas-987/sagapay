CREATE TYPE transfer_status AS ENUM
    ('INITIATED', 'RESERVED', 'POSTED', 'FAILED');
    -- SCREENING/CLEARED/BLOCKED/SETTLED/REVERSED exist in the full spec's
    -- TransferStatus but are out of reach in M2 (no fraud branch, no post-hoc
    -- compensation). Added to the enum by M3/M5 when a matching state actually
    -- becomes reachable, not before.

CREATE TABLE transfers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key      UUID NOT NULL,
    sender_id            UUID NOT NULL,
    sender_account_id    UUID NOT NULL,
    recipient_id         UUID NOT NULL,
    recipient_account_id UUID NOT NULL,
    amount               NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    currency             CHAR(3) NOT NULL,
    note                 VARCHAR(200),
    status               transfer_status NOT NULL DEFAULT 'INITIATED',
    reservation_id       UUID,
    failure_reason       VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_idem UNIQUE (sender_id, idempotency_key)
);
