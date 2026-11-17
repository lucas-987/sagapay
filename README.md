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

The Docker Compose stack runs the API gateway, the ledger, the transfer orchestrator and the
fraud service with their databases, Kafka, Redis and Keycloak. The gateway on port 8080 is the
entry a client uses: it checks the token and routes each call to the orchestrator or the
ledger. The ledger and the orchestrator start
with the `local` profile: the ledger seeds sample accounts (`marco` holds 150000.00 EUR).
The orchestrator and the ledger refuse a call without a valid token, and the orchestrator's own
calls to the ledger carry a token of its own, which the ledger's gRPC API admits. The ledger's
REST API serves each token's owner their own wallet.
The stack sends the client secret and the bearer tokens over plain HTTP and gRPC, with the realm's
`sslRequired` at `none`: that is for a local stack only, anywhere else both need TLS.

```bash
cp .env.example .env     # then fill in every empty password
cp infra/keycloak.env.example infra/keycloak.env    # and the sample users' passwords
docker compose --env-file .env -f infra/docker-compose.yml up --build
```

The published ports (8080 to 8083, 8180, the databases, Redis and Kafka) are bound on every
host interface, and the stack sends its tokens over plain HTTP: run the stack on a trusted
network only. They must be free on the host, so stop any other stack first.

Every service answers `GET /actuator/health` with `{"status":"UP"}`, with no token: gateway on
8080, ledger on 8081, orchestrator on 8082, fraud service on 8083. The ledger and the orchestrator refuse every
other Actuator endpoint, with or without a token.

Settings read from `.env` or the shell (a variable marked required makes every Compose command of the stack
fail while it is unset, `down`, `ps` and `logs` included, so keep it in `.env`):

| Variable | Meaning | Default |
|---|---|---|
| `KEYCLOAK_ADMIN_PASSWORD` | password of the Keycloak admin console (user `admin`, port 8180) | none, required |
| `ORCHESTRATOR_CLIENT_ID` | client id the orchestrator presents to Keycloak to call the ledger (`transfer-orchestrator`) | none, required |
| `ORCHESTRATOR_CLIENT_SECRET` | its secret: Keycloak imports it into the realm and the orchestrator presents it; neither starts without it | none, required |
| `SAGAPAY_FRAUD_VERIFICATION_CODE` | code the sender submits to confirm a blocked transfer; the curl commands below read it from the shell | none, required |
| `SAGA_CONFIRMATION_WINDOW_MS` | how long a blocked transfer waits for its confirmation, less if its reservation expires first | `120000` |
| `SAGA_SCREENING_TIMEOUT_MS` | how long the orchestrator waits for the fraud verdict | `10000` |
| `SAGA_CONFIRMATION_RESERVATION_MARGIN_MS` | time kept between a confirmation deadline and the expiry of the reservation | `5000` |
| `SAGA_REPRISE_BATCH_SIZE` | transfers a recovery pass takes per kind of work | `100` |
| `LEDGER_RESERVATION_TTL_MS` | how long a reservation holds its money, at least 60000 | `300000` |
| `FRAUD_RULES_VELOCITY_WINDOW_SECONDS` | length of the window the velocity rule counts over | `3600` |
| `GATEWAY_HOST_PORT` | host port the gateway is published on; the examples below assume the default | `8080` |
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
`docker compose --env-file .env -f infra/docker-compose.yml down -v`. Every call below goes
through the gateway on port 8080 with the token of the signed-in user, who is the sender
whatever the request says. The commands use `marco` as sender and `ines` as recipient. They
need `curl`, `jq` and `uuidgen`, the verification code in the shell, which `--env-file` does
not provide, and `marco`'s password (see "Getting a token"). The activity stream stays open,
so run it in a second terminal.

A call without a valid token is refused at the gateway:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/v1/wallet    # 401
```

Terminal 2, the activity stream of the signed-in user. Each event is pushed as it happens;
`-N` keeps `curl` from buffering it:

```bash
set -a; . infra/keycloak.env; set +a    # exports MARCO_PASSWORD
TOKEN=$(curl -s http://localhost:8180/realms/sagapay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sagapay-cli -d username=marco -d password="$MARCO_PASSWORD" \
  | jq -r .access_token)
curl -N -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' http://localhost:8080/v1/stream/activity
```

Terminal 1, the transfer:

```bash
set -a; . ./.env; . infra/keycloak.env; set +a    # exports SAGAPAY_FRAUD_VERIFICATION_CODE, MARCO_PASSWORD

# 0. Get marco's token: it names the sender of every call below.
TOKEN=$(curl -s http://localhost:8180/realms/sagapay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sagapay-cli -d username=marco -d password="$MARCO_PASSWORD" \
  | jq -r .access_token)

RECIPIENT=$(curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/v1/users/lookup?handle=ines' | jq -r .accountId)

# 1. Send the transfer: 202, the body holds the transfer id. The token expires after a few
#    minutes: get a new one when a call answers 401.
ID=$(curl -s -X POST http://localhost:8080/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d "{\"toUserId\": \"$RECIPIENT\", \"amount\": {\"currency\": \"EUR\", \"amount\": \"500.00\"}}" \
  | jq -r .id)

# 2. A moment later the stream shows `event: transfer.blocked`, and the transfer is flagged:
#    "status": "BLOCKED".
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/transfers/$ID | jq .status

# 3. Confirm it with the configured code: 202.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/v1/transfers/$ID/confirm \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"verificationToken\": \"$SAGAPAY_FRAUD_VERIFICATION_CODE\"}"

# 4. The stream shows `event: transfer.posted`, and the transfer is posted: "status": "POSTED".
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/transfers/$ID | jq .status

# 5. marco's wallet, with the new balance: the stream carries no balance, so it is read from the
#    ledger, which serves the wallet of the token's owner.
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/wallet | jq .
```

To watch a flagged transfer expire, repeat step 1 to another seeded recipient (`hugo`)
and do not confirm. Once the confirmation window has passed (two minutes by default;
shorten it with `SAGA_CONFIRMATION_WINDOW_MS`), the transfer shows
`"status": "FAILED"` with `"failureReason": "VERIFICATION_EXPIRED"`, and a confirm
answers `410`.

The end-to-end test `LocalStackFraudFlowE2ETest` drives this same stack from
`infra/docker-compose.yml` with a 10 second window: through the gateway for the flow above,
and on each service's own port to check that a service refuses a call without a token.
