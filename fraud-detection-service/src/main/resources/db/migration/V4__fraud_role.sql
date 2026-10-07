CREATE ROLE ${fraudAppUsername} LOGIN PASSWORD '${fraudAppPassword}';
GRANT SELECT, INSERT, UPDATE ON fraud_cases TO ${fraudAppUsername};
GRANT SELECT, INSERT, UPDATE ON outbox TO ${fraudAppUsername};
GRANT SELECT, INSERT ON processed_events TO ${fraudAppUsername};
