package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.grpc.FixedServiceTokenProvider;
import dev.treyer.sagapay.orchestrator.security.LedgerContainer;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole stack through the REST API, against a real ledger container: money
 * actually moves and the sum of balances is asserted unchanged.
 */
@Testcontainers
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class, FixedServiceTokenProvider.Configuration.class})
@TestPropertySource(properties = {"saga.reprise.grace-period-ms=0", "saga.reprise.sweep-interval-ms=1000"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ledger.grpc.deadline-ms=5000")
class TransferOrchestratorE2ETest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer LEDGER_POSTGRES = E2ESupport.ledgerPostgres(NETWORK);

    @Container
    private static final GenericContainer<?> LEDGER = E2ESupport.ledger(NETWORK, LEDGER_POSTGRES);

    @DynamicPropertySource
    static void ledgerGrpcTarget(DynamicPropertyRegistry registry) {
        E2ESupport.pointOrchestratorAt(registry, LEDGER);
    }

    @LocalServerPort
    private int orchestratorPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    private RestClient orchestratorClient;
    private RestClient ledgerClient;
    private static ManagedChannel ledgerChannel;
    private static LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub;

    @BeforeAll
    static void setUpLedgerGrpcStub() {
        ledgerChannel = ManagedChannelBuilder.forAddress(LEDGER.getHost(), LEDGER.getMappedPort(9091))
                .usePlaintext()
                .build();
        ledgerStub = LedgerServiceGrpc.newBlockingStub(ledgerChannel).withInterceptors(LedgerContainer.serviceToken());
    }

    @AfterAll
    static void tearDownLedgerGrpcStub() {
        if (ledgerChannel != null) {
            ledgerChannel.shutdownNow();
        }
    }

    private RestClient orchestrator() {
        if (orchestratorClient == null) {
            orchestratorClient = RestClient.create("http://localhost:" + orchestratorPort);
        }
        return orchestratorClient;
    }

    private RestClient ledger() {
        if (ledgerClient == null) {
            ledgerClient = RestClient.create("http://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(8081));
        }
        return ledgerClient;
    }

    private UUID lookupAccountId(String handle) {
        return E2ESupport.lookupAccountId(ledger(), handle);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return E2ESupport.balanceOf(ledger(), accountId);
    }

    private Map<?, ?> waitForStatus(UUID transferId, UUID senderId, String expectedStatus, Duration timeout) {
        return E2ESupport.waitForStatus(orchestrator(), transferId, senderId, expectedStatus, timeout);
    }

    private void clearOnceScreening(UUID transferId, UUID senderId) {
        E2ESupport.publishFraudResultOnceScreening(orchestrator(), kafka, transferId, senderId, "CLEARED");
    }

    @Test
    void postedTransferMovesMoneyAndKeepsTheSumOfBalancesUnchanged() {
        UUID senderId = lookupAccountId("gina"); // seeded 1200.00 EUR
        UUID recipientId = lookupAccountId("hugo"); // seeded 3400.00 EUR
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);

        Map<?, ?> created = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("Authorization", E2ESupport.bearer(senderId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "e2e happy path"}""".formatted(recipientId))
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) created.get("id"));
        assertThat(created.get("status")).isEqualTo("INITIATED");

        clearOnceScreening(transferId, senderId);
        waitForStatus(transferId, senderId, "POSTED", Duration.ofSeconds(15));

        BigDecimal senderAfter = balanceOf(senderId);
        BigDecimal recipientAfter = balanceOf(recipientId);
        assertThat(senderBefore.subtract(senderAfter)).isEqualByComparingTo("80.00");
        assertThat(recipientAfter.subtract(recipientBefore)).isEqualByComparingTo("80.00");
        assertThat(senderBefore.add(recipientBefore)).isEqualByComparingTo(senderAfter.add(recipientAfter));
    }

    @Test
    void replayingTheSameIdempotencyKeyReturns409AndMovesMoneyOnlyOnce() {
        UUID senderId = lookupAccountId("wojciech"); // seeded 42000.00 EUR
        UUID recipientId = lookupAccountId("lina"); // seeded 99999.99 EUR
        UUID idempotencyKey = UUID.randomUUID();
        BigDecimal senderBefore = balanceOf(senderId);

        String requestBody = """
                {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "25.00"}, "note": "replay test"}""".formatted(recipientId);

        Map<?, ?> first = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("Authorization", E2ESupport.bearer(senderId))
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) first.get("id"));
        clearOnceScreening(transferId, senderId);
        waitForStatus(transferId, senderId, "POSTED", Duration.ofSeconds(15));

        try {
            orchestrator()
                    .post()
                    .uri("/v1/transfers")
                    .header("Authorization", E2ESupport.bearer(senderId))
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            org.junit.jupiter.api.Assertions.fail("expected a 409 on replay");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(409);
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            assertThat(body.get("id")).isEqualTo(transferId.toString());
        }

        // Only one 25.00 debit, not two.
        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("25.00");
    }

    @Test
    void insufficientFundsFailsTheTransferAndReservesNothing() {
        UUID senderId = lookupAccountId("nora"); // seeded 0.05 EUR
        UUID recipientId = lookupAccountId("marco");
        BigDecimal senderBefore = balanceOf(senderId);

        Map<?, ?> created = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("Authorization", E2ESupport.bearer(senderId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}, "note": "not enough funds"}""".formatted(recipientId))
                .retrieve()
                .body(Map.class);
        UUID transferId = UUID.fromString((String) created.get("id"));

        waitForStatus(transferId, senderId, "FAILED", Duration.ofSeconds(15));

        assertThat(balanceOf(senderId)).isEqualByComparingTo(senderBefore); // nothing reserved, nothing moved
    }

    @Test
    void reprisePollerAlonePostsATransferForcedIntoClearedWithoutTheEagerPath() {
        UUID senderId = lookupAccountId("julien"); // seeded 15000.00 EUR
        UUID recipientId = lookupAccountId("tomasz");
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);
        UUID transferId = UUID.randomUUID();
        String ledgerTransferId = transferId.toString();

        // Reserved directly on the ledger: the eager path never runs.
        CheckAndReserveResponse reserved = ledgerStub.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(ledgerTransferId)
                .setFromAccountId(senderId.toString())
                .setToAccountId(recipientId.toString())
                .setAmount(dev.treyer.sagapay.common.v1.Money.newBuilder()
                        .setCurrency("EUR")
                        .setAmount("40.00")
                        .build())
                .build());
        assertThat(reserved.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        // Only the reprise poller can move this transfer on.
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, note, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLEARED', ?)
                """,
                transferId,
                UUID.randomUUID(),
                senderId,
                senderId,
                recipientId,
                recipientId,
                new BigDecimal("40.00"),
                "EUR",
                "crash recovery e2e",
                UUID.fromString(reserved.getReservationId()));

        waitForStatus(transferId, senderId, "POSTED", Duration.ofSeconds(15));

        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("40.00");
        assertThat(balanceOf(recipientId).subtract(recipientBefore)).isEqualByComparingTo("40.00");
    }

    /** Crash after the ledger reserved, before the reservation was recorded: the replayed
     * checkAndReserve returns the same reservation instead of holding twice. */
    @Test
    void reprisePollerAloneScreensATransferLeftInitiatedAfterTheLedgerAlreadyReserved() {
        UUID senderId = lookupAccountId("julien");
        UUID recipientId = lookupAccountId("tomasz");
        BigDecimal senderBefore = balanceOf(senderId);
        BigDecimal recipientBefore = balanceOf(recipientId);
        UUID transferId = UUID.randomUUID();

        CheckAndReserveResponse reserved = ledgerStub.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId.toString())
                .setFromAccountId(senderId.toString())
                .setToAccountId(recipientId.toString())
                .setAmount(dev.treyer.sagapay.common.v1.Money.newBuilder()
                        .setCurrency("EUR")
                        .setAmount("15.00")
                        .build())
                .build());
        assertThat(reserved.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, note, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'INITIATED')
                """,
                transferId,
                UUID.randomUUID(),
                senderId,
                senderId,
                recipientId,
                recipientId,
                new BigDecimal("15.00"),
                "EUR",
                "crash before the reservation was recorded e2e");

        clearOnceScreening(transferId, senderId);
        waitForStatus(transferId, senderId, "POSTED", Duration.ofSeconds(15));

        assertThat(senderBefore.subtract(balanceOf(senderId))).isEqualByComparingTo("15.00");
        assertThat(balanceOf(recipientId).subtract(recipientBefore)).isEqualByComparingTo("15.00");
        assertThat(jdbcTemplate.queryForObject(
                        "select reservation_id from transfers where id = ?", UUID.class, transferId))
                .isEqualTo(UUID.fromString(reserved.getReservationId()));
    }
}
