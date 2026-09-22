CREATE ROLE ${orchestratorAppUsername} LOGIN PASSWORD '${orchestratorAppPassword}';
GRANT SELECT, INSERT, UPDATE ON transfers TO ${orchestratorAppUsername};
GRANT SELECT, INSERT ON saga_steps TO ${orchestratorAppUsername};
GRANT SELECT, INSERT, UPDATE ON outbox TO ${orchestratorAppUsername};
-- No GRANT on saga_steps' identity sequence: verified empirically (throwaway
-- postgres:17 container) that GENERATED ALWAYS AS IDENTITY needs only
-- SELECT/INSERT on the table itself, not a separate sequence privilege.
