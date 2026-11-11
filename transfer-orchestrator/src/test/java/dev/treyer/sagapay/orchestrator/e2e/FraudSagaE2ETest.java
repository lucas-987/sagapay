package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.grpc.FixedServiceTokenProvider;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each screening outcome against a real ledger: the reservation ends consumed by one posting or
 * released, and the sum of all balances never changes. The fraud results are published by the
 * test; the screening deadline and the confirmation window are shortened.
 */
@Testcontainers
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class, FixedServiceTokenProvider.Configuration.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ledger.grpc.deadline-ms=5000",
            "saga.screening.timeout-ms=8000",
            "saga.confirmation.window-ms=8000",
            "saga.deadline.sweep-interval-ms=500"
        })
class FraudSagaE2ETest {

    private static final Network NETWORK = Network.newNetwork();
    private static final String VERIFICATION_CODE = "test-verification-code";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    private static final PostgreSQLContainer LEDGER_POSTGRES = E2ESupport.ledgerPostgres(NETWORK);

    @Container
    private static final GenericContainer<?> LEDGER = E2ESupport.ledger(NETWORK, LEDGER_POSTGRES);

    @DynamicPropertySource
    static void ledgerTargets(DynamicPropertyRegistry registry) {
        E2ESupport.pointOrchestratorAt(registry, LEDGER);
    }

    @LocalServerPort
    private int orchestratorPort;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    private RestClient orchestrator;
    private RestClient ledger;
    private JdbcTemplate ledgerDb;

    private RestClient orchestrator() {
        if (orchestrator == null) {
            orchestrator = RestClient.create("http://localhost:" + orchestratorPort);
        }
        return orchestrator;
    }

    private RestClient ledger() {
        if (ledger == null) {
            ledger = RestClient.create("http://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(8081));
        }
        return ledger;
    }

    private JdbcTemplate ledgerDb() {
        if (ledgerDb == null) {
            ledgerDb = new JdbcTemplate(new DriverManagerDataSource(
                    LEDGER_POSTGRES.getJdbcUrl(), LEDGER_POSTGRES.getUsername(), LEDGER_POSTGRES.getPassword()));
        }
        return ledgerDb;
    }

    private UUID account(String handle) {
        return E2ESupport.lookupAccountId(ledger(), handle);
    }

    private BigDecimal sumOfAllBalances() {
        return ledgerDb().queryForObject("select sum(balance) from accounts", BigDecimal.class);
    }

    private UUID transfer(UUID senderId, UUID recipientId, String amount) {
        Map<?, ?> created = orchestrator()
                .post()
                .uri("/v1/transfers")
                .header("Authorization", E2ESupport.bearer(senderId))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "%s"}, "note": "fraud saga e2e"}""".formatted(recipientId, amount))
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) created.get("id"));
    }

    private void confirm(UUID transferId, UUID senderId) {
        orchestrator()
                .post()
                .uri("/v1/transfers/{id}/confirm", transferId)
                .header("Authorization", E2ESupport.bearer(senderId))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"verificationToken\": \"%s\"}".formatted(VERIFICATION_CODE))
                .retrieve()
                .toBodilessEntity();
    }

    private void waitForStatus(UUID transferId, UUID senderId, String status) {
        E2ESupport.waitForStatus(orchestrator(), transferId, senderId, status, WAIT);
    }

    private Map<?, ?> failedTransfer(UUID transferId, UUID senderId) {
        return E2ESupport.waitForStatus(orchestrator(), transferId, senderId, "FAILED", WAIT);
    }

    private UUID flagged(UUID senderId, UUID recipientId, String amount) {
        UUID transferId = transfer(senderId, recipientId, amount);
        E2ESupport.publishFraudResultOnceScreening(orchestrator(), kafka, transferId, senderId, "FLAGGED");
        waitForStatus(transferId, senderId, "BLOCKED");
        return transferId;
    }

    private String reservationStatus(UUID transferId) {
        return ledgerDb()
                .queryForObject(
                        "select status from reservations where transfer_id = ?", String.class, transferId.toString());
    }

    private int postingCount(UUID transferId) {
        return ledgerDb()
                .queryForObject(
                        "select count(*) from postings where transfer_id = ?", Integer.class, transferId.toString());
    }

    private record Wallets(BigDecimal senderBalance, BigDecimal senderAvailable, BigDecimal recipientBalance) {}

    private Wallets walletsOf(UUID senderId, UUID recipientId) {
        return new Wallets(
                E2ESupport.balanceOf(ledger(), senderId),
                E2ESupport.availableOf(ledger(), senderId),
                E2ESupport.balanceOf(ledger(), recipientId));
    }

    private void assertUntouched(UUID senderId, UUID recipientId, Wallets before) {
        assertThat(walletsOf(senderId, recipientId)).isEqualTo(before);
    }

    @Test
    @Tag("M3-S38")
    @DisplayName("A flagged transfer confirmed in time moves the money once")
    void flaggedTransferConfirmedInTimeMovesTheMoneyOnce() {
        UUID senderId = account("gina");
        UUID recipientId = account("hugo");
        BigDecimal amount = new BigDecimal("80.00");
        Wallets before = walletsOf(senderId, recipientId);
        BigDecimal sumBefore = sumOfAllBalances();

        UUID transferId = flagged(senderId, recipientId, "80.00");

        assertThat(E2ESupport.availableOf(ledger(), senderId))
                .isEqualByComparingTo(before.senderAvailable().subtract(amount));
        assertThat(E2ESupport.balanceOf(ledger(), senderId)).isEqualByComparingTo(before.senderBalance());
        assertThat(reservationStatus(transferId)).isEqualTo("ACTIVE");

        confirm(transferId, senderId);
        waitForStatus(transferId, senderId, "POSTED");

        assertThat(E2ESupport.balanceOf(ledger(), senderId))
                .isEqualByComparingTo(before.senderBalance().subtract(amount));
        assertThat(E2ESupport.balanceOf(ledger(), recipientId))
                .isEqualByComparingTo(before.recipientBalance().add(amount));
        assertThat(postingCount(transferId)).isEqualTo(2);
        assertThat(reservationStatus(transferId)).isEqualTo("CONSUMED");
        assertThat(sumOfAllBalances()).isEqualByComparingTo(sumBefore);
    }

    @Test
    @Tag("M3-S39")
    @DisplayName("A flagged transfer left to expire gives the money back")
    void flaggedTransferLeftToExpireGivesTheMoneyBack() {
        UUID senderId = account("wojciech");
        UUID recipientId = account("lina");
        Wallets before = walletsOf(senderId, recipientId);
        BigDecimal sumBefore = sumOfAllBalances();

        UUID transferId = flagged(senderId, recipientId, "25.00");

        Map<?, ?> failed = failedTransfer(transferId, senderId);

        assertThat(failed.get("failureReason")).isEqualTo("VERIFICATION_EXPIRED");
        assertThat(reservationStatus(transferId)).isEqualTo("RELEASED");
        assertUntouched(senderId, recipientId, before);
        assertThat(postingCount(transferId)).isZero();
        assertThatThrownBy(() -> confirm(transferId, senderId))
                .isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(410);
                    assertThat(e.getResponseBodyAs(Map.class).get("code")).isEqualTo("VERIFICATION_EXPIRED");
                });
        assertThat(sumOfAllBalances()).isEqualByComparingTo(sumBefore);
    }

    @Test
    @Tag("M3-S40")
    @DisplayName("A transfer fraud never answers gives the money back")
    void transferFraudNeverAnswersGivesTheMoneyBack() {
        UUID senderId = account("julien");
        UUID recipientId = account("tomasz");
        Wallets before = walletsOf(senderId, recipientId);
        BigDecimal sumBefore = sumOfAllBalances();

        UUID transferId = transfer(senderId, recipientId, "40.00");
        Map<?, ?> failed = failedTransfer(transferId, senderId);

        assertThat(failed.get("failureReason")).isEqualTo("SCREENING_TIMEOUT");
        assertThat(reservationStatus(transferId)).isEqualTo("RELEASED");
        assertUntouched(senderId, recipientId, before);
        assertThat(postingCount(transferId)).isZero();
        assertThat(sumOfAllBalances()).isEqualByComparingTo(sumBefore);
    }

    @Test
    @Tag("M3-S41")
    @DisplayName("A cleared transfer moves the money once")
    void clearedTransferMovesTheMoneyOnce() {
        UUID senderId = account("marco");
        UUID recipientId = account("ines");
        BigDecimal amount = new BigDecimal("60.00");
        Wallets before = walletsOf(senderId, recipientId);
        BigDecimal sumBefore = sumOfAllBalances();

        UUID transferId = transfer(senderId, recipientId, "60.00");
        E2ESupport.publishFraudResultOnceScreening(orchestrator(), kafka, transferId, senderId, "CLEARED");
        waitForStatus(transferId, senderId, "POSTED");

        assertThat(E2ESupport.balanceOf(ledger(), senderId))
                .isEqualByComparingTo(before.senderBalance().subtract(amount));
        assertThat(E2ESupport.balanceOf(ledger(), recipientId))
                .isEqualByComparingTo(before.recipientBalance().add(amount));
        assertThat(postingCount(transferId)).isEqualTo(2);
        assertThat(reservationStatus(transferId)).isEqualTo("CONSUMED");
        assertThat(sumOfAllBalances()).isEqualByComparingTo(sumBefore);
    }
}
