# SagaPay — peer-to-peer payments

Maven multi-module: `common`, `contracts`, `ledger-accounts-service`,
`transfer-orchestrator`, `fraud-detection-service`, `api-gateway`,
`notification-consumer`.

## Contracts (`contracts`)

The `contracts` module is the source of truth for the interfaces (gRPC proto +
OpenAPI); the other modules compile against the code generated from it.

### Regenerating the contracts

```bash
./mvnw -pl contracts generate-sources
```

### Where the generated sources land

Everything under `contracts/target/generated-sources/` (not versioned,
regenerated on every build):

- `protobuf/` — gRPC messages + stubs generated from `src/main/proto/**`.
- `openapi-transfer/src/main/java/` — Spring MVC interfaces generated from
  `src/main/resources/openapi/transfer-service.openapi.yaml`.
- `openapi-ledger/src/main/java/` — same, generated from
  `ledger-accounts-service.openapi.yaml`.

Both OpenAPI executions run in `interfaceOnly` mode: only the interfaces to
implement are generated, no controller.

## Running the stack locally

The Docker Compose stack runs the ledger, the transfer orchestrator and the fraud
service with their databases, Kafka and Redis. The ledger and the orchestrator start
with the `local,local-noauth` profiles: the ledger seeds sample accounts (`marco` holds
150000.00 EUR) and both open their REST endpoints without credentials until
authentication lands.

```bash
cp .env.example .env     # then fill in every empty password
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

The published ports (8081 to 8083, the databases, Redis and Kafka) are bound on every
host interface and the `local-noauth` profile asks for no credentials: run the stack on
a trusted network only. They must be free on the host, so stop any other stack first.

Every service answers `GET /actuator/health` with `{"status":"UP"}`: ledger on 8081,
orchestrator on 8082, fraud service on 8083.

Settings read from `.env` or the shell:

| Variable | Meaning | Default |
|---|---|---|
| `SAGAPAY_FRAUD_VERIFICATION_CODE` | code the sender submits to confirm a blocked transfer; the curl commands below read it from the shell | none, required |
| `SAGA_CONFIRMATION_WINDOW_MS` | how long a blocked transfer waits for its confirmation | `120000` |
| `SAGA_SCREENING_TIMEOUT_MS` | how long the orchestrator waits for the fraud verdict | `10000` |
| `MAVEN_MIRROR_URL` | optional Maven mirror used by the image builds | empty |

## Trying the fraud flow

With the default fraud rules, 500.00 EUR sent to a recipient the sender never paid is
flagged. This holds once per recipient: after the transfer is posted the recipient is no
longer new and the same amount is not flagged again. To start over, reset the stack with
`docker compose --env-file .env -f infra/docker-compose.yml down -v`. The commands below
use `marco` as sender and `ines` as recipient; the transfer endpoints take the sender in
`X-User-Id`. They need `curl`, `jq` and `uuidgen`, and the verification code in the
shell, which `--env-file` does not provide:

```bash
set -a; . ./.env; set +a    # exports SAGAPAY_FRAUD_VERIFICATION_CODE

SENDER=$(curl -s 'http://localhost:8081/v1/users/lookup?handle=marco' | jq -r .accountId)
RECIPIENT=$(curl -s 'http://localhost:8081/v1/users/lookup?handle=ines' | jq -r .accountId)

# 1. Send the transfer: 202, the body holds the transfer id.
ID=$(curl -s -X POST http://localhost:8082/v1/transfers \
  -H "X-User-Id: $SENDER" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d "{\"toUserId\": \"$RECIPIENT\", \"amount\": {\"currency\": \"EUR\", \"amount\": \"500.00\"}}" \
  | jq -r .id)

# 2. A moment later the transfer is flagged: "status": "BLOCKED".
curl -s http://localhost:8082/v1/transfers/$ID | jq .status

# 3. Confirm it with the configured code: 202.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/v1/transfers/$ID/confirm \
  -H "X-User-Id: $SENDER" -H 'Content-Type: application/json' \
  -d "{\"verificationToken\": \"$SAGAPAY_FRAUD_VERIFICATION_CODE\"}"

# 4. The transfer is posted: "status": "POSTED".
curl -s http://localhost:8082/v1/transfers/$ID | jq .status
```

To watch a flagged transfer expire, repeat step 1 to another seeded recipient (`hugo`)
and do not confirm. Once the confirmation window has passed (two minutes by default;
shorten it with `SAGA_CONFIRMATION_WINDOW_MS`), the transfer shows
`"status": "FAILED"` with `"failureReason": "VERIFICATION_EXPIRED"`, and a confirm
answers `410`.

The end-to-end test `LocalStackFraudFlowE2ETest` drives this same stack from
`infra/docker-compose.yml` with a 10 second window.
