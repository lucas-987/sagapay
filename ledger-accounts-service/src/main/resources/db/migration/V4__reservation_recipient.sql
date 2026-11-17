-- Nothing to carry over: no deployed reservation predates the recipient.
ALTER TABLE reservations ADD COLUMN to_account_id UUID NOT NULL;
