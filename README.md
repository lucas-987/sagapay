# SagaPay — peer-to-peer payments

Maven multi-module: `common`, `contracts`, `ledger-accounts-service`,
`transfer-orchestrator`, `fraud-detection-service`, `api-gateway`.

## Repository

Part of **SagaPay**. Specification, milestones and design decisions:
[sagapay-spec](https://github.com/lucas-987/sagapay-spec). Specified and delivered with
[specwrit](https://github.com/lucas-987/specwrit).

| | |
|---|---|
| Role | backend services, contracts, local infrastructure |
| Stack | Java 21, Spring Boot 4, Maven multi-module |
| Related repositories | — |

## Package layout

The three service modules (`ledger-accounts-service`, `transfer-orchestrator`,
`fraud-detection-service`) are hexagonal, under `dev.treyer.sagapay.ledger`,
`.orchestrator` and `.fraud`:

```
domain/         entities, value objects, pure logic
application/
  port/in/      use cases the domain exposes
  port/out/     what the application needs from the outside
  service/      implements the in ports, owns the transaction
adapter/
  in/           grpc, rest, kafka, scheduler: call the in ports
  out/          persistence, grpc, rest, redis, messaging: implement the out ports
config/
```

Dependencies point inwards only: adapters depend on the application, the application on
the domain. JPA entities live in `domain/` and are not duplicated as pure domain classes
behind a mapper.

## Contracts

The `contracts` module holds the definitions several modules compile against: the gRPC
proto files. Each service owns its OpenAPI document and generates its own Spring MVC
interfaces from it, in `interfaceOnly` mode (no controller is generated):

- `ledger-accounts-service/src/main/resources/openapi/ledger-accounts-service.openapi.yaml`
- `transfer-orchestrator/src/main/resources/openapi/transfer-service.openapi.yaml`

### Regenerating the contracts

```bash
./mvnw -pl contracts,ledger-accounts-service,transfer-orchestrator generate-sources
```

Generated sources are under each module's `target/generated-sources/` (not versioned,
regenerated on every build): `protobuf/` in `contracts`, `openapi-ledger/` in
`ledger-accounts-service`, `openapi-transfer/` in `transfer-orchestrator`.

## Running the stack locally

The Docker Compose stack runs the ledger, the transfer orchestrator and the fraud
service with their databases, Kafka and Redis. The ledger and the orchestrator start
with the `local,local-noauth` profiles: the ledger seeds sample accounts (`marco` holds
150000.00 EUR) and both open their REST endpoints without credentials until
authentication lands.

```bash
cp .env.example .env     # then fill in every empty password
cp infra/keycloak.env.example infra/keycloak.env    # and the sample users' passwords
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

The published ports (8081 to 8083, 8180, the databases, Redis and Kafka) are bound on every
host interface and the `local-noauth` profile asks for no credentials: run the stack on
a trusted network only. They must be free on the host, so stop any other stack first.

Every service answers `GET /actuator/health` with `{"status":"UP"}`: ledger on 8081,
orchestrator on 8082, fraud service on 8083.

Settings read from `.env` or the shell:

| Variable | Meaning | Default |
|---|---|---|
| `KEYCLOAK_ADMIN_PASSWORD` | password of the Keycloak admin console (user `admin`, port 8180) | none, required |
| `SAGAPAY_FRAUD_VERIFICATION_CODE` | code the sender submits to confirm a blocked transfer; the curl commands below read it from the shell | none, required |
| `SAGA_CONFIRMATION_WINDOW_MS` | how long a blocked transfer waits for its confirmation, less if its reservation expires first | `120000` |
| `SAGA_SCREENING_TIMEOUT_MS` | how long the orchestrator waits for the fraud verdict | `10000` |
| `SAGA_CONFIRMATION_RESERVATION_MARGIN_MS` | time kept between a confirmation deadline and the expiry of the reservation | `5000` |
| `SAGA_REPRISE_BATCH_SIZE` | transfers a recovery pass takes per kind of work | `100` |
| `LEDGER_RESERVATION_TTL_MS` | how long a reservation holds its money, at least 60000 | `300000` |
| `FRAUD_RULES_VELOCITY_WINDOW_SECONDS` | length of the window the velocity rule counts over | `3600` |
| `MAVEN_MIRROR_URL` | optional Maven mirror used by the image builds | empty |

## Getting a token

Compose also starts Keycloak on port 8180 with the `sagapay` realm. Its users `marco`, `ines`
and `hugo` have the passwords you set in `infra/keycloak.env` (`MARCO_PASSWORD`, `INES_PASSWORD`
and `HUGO_PASSWORD`), and each one's id is the id of the sample account with the same handle. The public client `sagapay-cli` allows getting a token from the command
line:

```bash
set -a; . infra/keycloak.env; set +a    # exports MARCO_PASSWORD
curl -s http://localhost:8180/realms/sagapay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sagapay-cli -d username=marco -d password="$MARCO_PASSWORD" \
  | jq -r .access_token
```

The token's `sub` is `marco`'s account id and its issuer is `http://keycloak:8180/realms/sagapay`
whichever address the token was fetched from. A browser that must follow a Keycloak link needs
`127.0.0.1 keycloak` in its hosts file.

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
curl -s -H "X-User-Id: $SENDER" http://localhost:8082/v1/transfers/$ID | jq .status

# 3. Confirm it with the configured code: 202.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/v1/transfers/$ID/confirm \
  -H "X-User-Id: $SENDER" -H 'Content-Type: application/json' \
  -d "{\"verificationToken\": \"$SAGAPAY_FRAUD_VERIFICATION_CODE\"}"

# 4. The transfer is posted: "status": "POSTED".
curl -s -H "X-User-Id: $SENDER" http://localhost:8082/v1/transfers/$ID | jq .status
```

To watch a flagged transfer expire, repeat step 1 to another seeded recipient (`hugo`)
and do not confirm. Once the confirmation window has passed (two minutes by default;
shorten it with `SAGA_CONFIRMATION_WINDOW_MS`), the transfer shows
`"status": "FAILED"` with `"failureReason": "VERIFICATION_EXPIRED"`, and a confirm
answers `410`.

The end-to-end test `LocalStackFraudFlowE2ETest` drives this same stack from
`infra/docker-compose.yml` with a 10 second window.
