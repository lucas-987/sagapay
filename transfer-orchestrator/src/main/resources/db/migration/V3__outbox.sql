CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(32) NOT NULL,   -- 'Transfer'
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(32) NOT NULL,   -- TransferInitiated | FundsReserved | TransferPosted | TransferFailed
    payload        JSONB NOT NULL,
    headers        JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
