CREATE TABLE saga_steps (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  UUID NOT NULL REFERENCES transfers(id),
    at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    step         VARCHAR(16) NOT NULL,   -- RESERVE | POST
    outcome      VARCHAR(16) NOT NULL,   -- OK | FAILED
    detail       JSONB
);
