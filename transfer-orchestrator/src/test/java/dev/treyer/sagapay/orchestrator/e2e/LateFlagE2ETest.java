package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport;
import dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.Event;
import dev.treyer.sagapay.orchestrator.adapter.out.grpc.FixedServiceTokenProvider;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * A fraud flag that arrives when the transfer cannot be blocked, against a real ledger.
 * The confirmation margin exceeds the ledger's reservation lifetime, so a flag on a screening
 * transfer finds no time left to confirm.
 */
@Testcontainers
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class, FixedServiceTokenProvider.Configuration.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ledger.grpc.deadline-ms=5000",
            "saga.screening.timeout-ms=8000",
            "saga.confirmation.window-ms=8000",
            "saga.confirmation.reservation-margin-ms=3600000",
            "saga.deadline.sweep-interval-ms=500"
        })
class LateFlagE2ETest {

    private static final Network NETWORK = Network.newNetwork();
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

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private JdbcTemplate orchestratorDb;

    private enum Situation {
        ALREADY_FAILED_ITS_SCREENING_TIME("gina", "hugo"),
        RESERVATION_LEAVES_NO_TIME("julien", "tomasz");

        private final String sender;
        private final String recipient;

        Situation(String sender, String recipient) {
            this.sender = sender;
            this.recipient = recipient;
        }
    }

    private RestClient orchestrator() {
        return RestClient.create("http://localhost:" + orchestratorPort);
    }

    private RestClient ledger() {
        return RestClient.create("http://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(8081));
    }

    private UUID transfer(UUID senderId, UUID recipientId) {
        return UUID.fromString(
                (String) E2ESupport.send(orchestrator(), senderId, recipientId, "80.00", UUID.randomUUID())
                        .getBody()
                        .get("id"));
    }

    private UUID flagTheTransfer(Situation situation, UUID transferId, UUID senderId) {
        if (situation == Situation.ALREADY_FAILED_ITS_SCREENING_TIME) {
            E2ESupport.waitForStatus(orchestrator(), transferId, senderId, "FAILED", WAIT);
            return E2ESupport.publishFraudResult(kafka, transferId, "FLAGGED");
        }
        return E2ESupport.publishFraudResultOnceScreening(orchestrator(), kafka, transferId, senderId, "FLAGGED");
    }

    private int processedCount(UUID eventId) {
        return orchestratorDb.queryForObject(
                "select count(*) from processed_events where event_id = ?", Integer.class, eventId);
    }

    private int unpublishedOutboxRows(UUID transferId) {
        return orchestratorDb.queryForObject(
                "select count(*) from outbox where aggregate_id = ? and published_at is null",
                Integer.class,
                transferId);
    }

    private int blockedOutboxRows(UUID transferId) {
        return orchestratorDb.queryForObject(
                "select count(*) from outbox where aggregate_id = ? and event_type = 'TransferBlocked'",
                Integer.class,
                transferId);
    }

    @ParameterizedTest
    @EnumSource(Situation.class)
    @Tag("M4-S20")
    @DisplayName("A flag that blocks nothing is never shown as blocked")
    void aFlagThatBlocksNothingIsNeverShownAsBlocked(Situation situation) throws Exception {
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
        UUID senderId = E2ESupport.lookupAccountId(ledger(), situation.sender);
        UUID recipientId = E2ESupport.lookupAccountId(ledger(), situation.recipient);
        HttpClient http = HttpClient.newHttpClient();
        try (ActivityStreamSupport.Stream senderStream =
                        new ActivityStreamSupport.Stream(http, orchestratorPort, senderId);
                ActivityStreamSupport.Stream recipientStream =
                        new ActivityStreamSupport.Stream(http, orchestratorPort, recipientId)) {
            UUID transferId = transfer(senderId, recipientId);

            UUID flagEventId = flagTheTransfer(situation, transferId, senderId);

            await().atMost(WAIT).until(() -> processedCount(flagEventId) == 1);
            await().atMost(WAIT).until(() -> unpublishedOutboxRows(transferId) == 0);
            // Events of one sender share a partition: the sentinel arrives after everything published before.
            UUID sentinelTransfer = UUID.randomUUID();
            UUID sentinelId = UUID.randomUUID();
            ActivityStreamSupport.publish(
                    kafka, sentinelId, "TransferPosted.v1", sentinelTransfer, senderId, recipientId, null);

            String cause = situation == Situation.ALREADY_FAILED_ITS_SCREENING_TIME
                    ? "SCREENING_TIMEOUT"
                    : "VERIFICATION_EXPIRED";
            List<Event> senderSaw = senderStream.take(2);
            List<Event> recipientSaw = recipientStream.take(2);
            assertThat(senderSaw)
                    .extracting(Event::name, Event::data)
                    .containsExactly(
                            tuple(
                                    "transfer.failed",
                                    ActivityStreamSupport.dataWithCause(transferId, senderId, recipientId, cause)),
                            tuple(
                                    "transfer.posted",
                                    ActivityStreamSupport.data(sentinelTransfer, senderId, recipientId)));
            assertThat(recipientSaw)
                    .extracting(Event::name, Event::data)
                    .containsExactly(
                            tuple("transfer.failed", ActivityStreamSupport.data(transferId, senderId, recipientId)),
                            tuple(
                                    "transfer.posted",
                                    ActivityStreamSupport.data(sentinelTransfer, senderId, recipientId)));
            assertThat(blockedOutboxRows(transferId)).isZero();
        }
    }
}
