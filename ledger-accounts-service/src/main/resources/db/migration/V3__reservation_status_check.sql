ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED'));
