package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Real port, PostgreSQL and Kafka: a transfer blocked through the whole chain, read by both parties. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class ConfirmationDeadlineDeliveryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplyFraudResultUseCase fraudResults;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @MockitoBean
    private LedgerPort ledger;

    private UUID blockedTransfer(UUID sender, UUID recipient, Instant reservationExpiresAt) throws Exception {
        when(ledger.checkAndReserve(anyString(), any(), any(), any(Money.class)))
                .thenReturn(new ReservationResult.Ok(UUID.randomUUID(), reservationExpiresAt));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}}""".formatted(recipient)))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Authorization", "Bearer " + TestTokens.forSubject(sender))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        UUID transferId = UUID.fromString(
                (String) JSON.readValue(response.body(), Map.class).get("id"));
        awaitStatus(transferId, "SCREENING");
        fraudResults.applyFraudResult(new FraudResult(
                UUID.randomUUID(), transferId, FraudDecision.FLAGGED, new BigDecimal("0.9000"), List.of("VELOCITY")));
        awaitStatus(transferId, "BLOCKED");
        return transferId;
    }

    private void awaitStatus(UUID transferId, String status) {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> status.equals(
                        jdbc.queryForObject("select status from transfers where id = ?", String.class, transferId)));
    }

    private Map<?, ?> detail(UUID reader, UUID transferId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/transfers/" + transferId))
                .header("Authorization", "Bearer " + TestTokens.forSubject(reader))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readValue(response.body(), Map.class);
    }

    @Test
    @DisplayName("The sender reads the same confirmation deadline on the stream and in the detail, the recipient none")
    void theSenderReadsTheSameDeadlineOnTheStreamAndInTheDetail() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Instant reservationExpiresAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
        try (ActivityStreamSupport.Stream senderStream = new ActivityStreamSupport.Stream(http, port, sender);
                ActivityStreamSupport.Stream recipientStream =
                        new ActivityStreamSupport.Stream(http, port, recipient)) {
            UUID transferId = blockedTransfer(sender, recipient, reservationExpiresAt);

            ActivityStreamSupport.Event senderEvent = senderStream.take(1).getFirst();
            ActivityStreamSupport.Event recipientEvent = recipientStream.take(1).getFirst();

            Instant expected = reservationExpiresAt.minusMillis(5000);
            assertThat(senderEvent.name()).isEqualTo("transfer.blocked");
            assertThat(Instant.parse((String) senderEvent.data().get("confirmationDeadline")))
                    .isEqualTo(expected);
            assertThat(Instant.parse((String) detail(sender, transferId).get("confirmationDeadline")))
                    .isEqualTo(expected);
            assertThat(jdbc.queryForObject(
                                    "select deadline from saga_steps where transfer_id = ? and step = 'SCREEN'"
                                            + " order by id desc limit 1",
                                    Timestamp.class,
                                    transferId)
                            .toInstant())
                    .isEqualTo(expected);
            assertThat(recipientEvent.name()).isEqualTo("transfer.blocked");
            assertThat(recipientEvent.data()).isEqualTo(ActivityStreamSupport.data(transferId, sender, recipient));
            assertThat(detail(recipient, transferId).get("confirmationDeadline"))
                    .isNull();
        }
    }
}
