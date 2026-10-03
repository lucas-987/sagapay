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
