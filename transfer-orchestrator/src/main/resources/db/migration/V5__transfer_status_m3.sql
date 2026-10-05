-- Alone in its migration: Postgres rejects using a new enum value in the
-- transaction that adds it.
ALTER TYPE transfer_status ADD VALUE 'SCREENING';
ALTER TYPE transfer_status ADD VALUE 'CLEARED';
ALTER TYPE transfer_status ADD VALUE 'BLOCKED';
