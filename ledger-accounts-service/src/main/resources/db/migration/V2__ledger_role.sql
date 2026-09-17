CREATE ROLE ${ledgerAppUsername} LOGIN PASSWORD '${ledgerAppPassword}';
GRANT SELECT, INSERT ON postings TO ${ledgerAppUsername};
GRANT SELECT, INSERT, UPDATE ON accounts, reservations TO ${ledgerAppUsername};
GRANT SELECT, INSERT ON ledger_idempotency TO ${ledgerAppUsername};
