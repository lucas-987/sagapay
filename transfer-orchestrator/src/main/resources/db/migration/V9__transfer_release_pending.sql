-- Failed transfers only pile up while pending releases stay rare: the flag keeps their search off
-- the history. A partial index cannot read saga_steps, so the state has to live on the transfer.
ALTER TABLE transfers ADD COLUMN release_pending BOOLEAN NOT NULL DEFAULT false;

UPDATE transfers t SET release_pending = true
WHERE t.status = 'FAILED'
  AND (SELECT s.outcome FROM saga_steps s
       WHERE s.transfer_id = t.id AND s.step = 'RELEASE'
       ORDER BY s.id DESC LIMIT 1) = 'RETRY';

CREATE INDEX idx_transfers_release_pending ON transfers(updated_at, id) WHERE release_pending;
