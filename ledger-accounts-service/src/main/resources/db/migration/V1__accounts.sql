CREATE TABLE accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    handle        VARCHAR(64) NOT NULL UNIQUE,
    display_name  VARCHAR(128) NOT NULL,
    currency      CHAR(3) NOT NULL,
    balance       NUMERIC(20,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES accounts(id),
    transfer_id   VARCHAR(64) NOT NULL,
    amount        NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | CONSUMED | RELEASED | EXPIRED
    expires_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_account_active
    ON reservations(account_id) WHERE status = 'ACTIVE';

CREATE TABLE postings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_group   UUID NOT NULL,        -- ties the debit and credit of the same operation
    account_id    UUID NOT NULL REFERENCES accounts(id),
    transfer_id   VARCHAR(64) NOT NULL,
    leg           VARCHAR(8) NOT NULL,  -- DEBIT | CREDIT
    amount        NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Append-only by convention (no application code updates/deletes a posting), not enforced at the DB level.

CREATE TABLE ledger_idempotency (
    transfer_id   VARCHAR(64) NOT NULL,
    operation     VARCHAR(32) NOT NULL,  -- RESERVE | POST | RELEASE
    result_json   TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transfer_id, operation)
);