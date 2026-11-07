-- The application writes these values from enums; the constraints stop any other writer, or a
-- value added to an enum without its migration.
ALTER TABLE saga_steps
    ADD CONSTRAINT chk_saga_steps_step CHECK (step IN ('RESERVE', 'SCREEN', 'POST', 'RELEASE')),
    ADD CONSTRAINT chk_saga_steps_outcome CHECK (outcome IN ('OK', 'FAILED', 'RETRY', 'COMPENSATED'));

ALTER TABLE transfers
    ADD CONSTRAINT chk_transfers_failure_reason CHECK (failure_reason IN (
        'INSUFFICIENT_FUNDS', 'RESERVE_REJECTED', 'POST_REJECTED',
        'SCREENING_TIMEOUT', 'VERIFICATION_EXPIRED')),
    ADD CONSTRAINT chk_transfers_fraud_score CHECK (fraud_score BETWEEN 0 AND 1);

-- Postgres does not index a foreign key, and every saga query reads the latest steps of a transfer.
CREATE INDEX idx_saga_steps_transfer_latest ON saga_steps(transfer_id, id DESC);

-- The sweeps only look for transfers still in flight, a small part of a table that keeps growing.
CREATE INDEX idx_transfers_in_flight ON transfers(status, updated_at)
    WHERE status IN ('INITIATED', 'SCREENING', 'BLOCKED', 'CLEARED');
