package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class SendIdempotencyTest {

    private static final UUID RECIPIENT = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000c3");
    private static final UUID ANOTHER_RECIPIENT = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000c4");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplyFraudResultUseCase fraudResults;

    @MockitoBean
    private LedgerPort ledger;

    @MockitoBean
    private AccountLookupPort accounts;

    @BeforeEach
    void ledgerReservesAndKnowsLina() {
        when(ledger.checkAndReserve(anyString(), any(), any(), any(Money.class)))
                .thenReturn(new ReservationResult.Ok(
                        UUID.randomUUID(), Instant.now().plusSeconds(600)));
        when(accounts.lookupByHandle("lina")).thenReturn(Optional.of(RECIPIENT));
    }

    private static String toUser(UUID recipient, String amount, String currency, String note) {
        return body("\"toUserId\": \"" + recipient + "\"", amount, currency, note);
    }

    private static String toHandle(String handle, String amount, String currency, String note) {
        return body("\"toHandle\": \"" + handle + "\"", amount, currency, note);
    }

    private static String body(String recipient, String amount, String currency, String note) {
        String noteField = note == null ? "" : ", \"note\": \"" + note + "\"";
        return "{" + recipient + ", \"amount\": {\"currency\": \"" + currency + "\", \"amount\": \"" + amount + "\"}"
                + noteField + "}";
    }

    private static String eur80(UUID recipient) {
        return toUser(recipient, "80.00", "EUR", "pizza");
    }

    private HttpResponse<String> send(UUID sender, UUID key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TestTokens.forSubject(sender));
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<?, ?> json(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Map.class);
    }

    private int transfersOf(UUID sender) {
        return jdbc.queryForObject("select count(*) from transfers where sender_id = ?", Integer.class, sender);
    }

    private List<String> transferIdsOf(UUID sender) {
        return jdbc.queryForList("select id::text from transfers where sender_id = ?", String.class, sender);
    }

    private int outboxRowsOf(String transferId) {
        return jdbc.queryForObject(
                "select count(*) from outbox where aggregate_id = ?::uuid", Integer.class, transferId);
    }

    private int sagaStepsOf(String transferId) {
        return jdbc.queryForObject(
                "select count(*) from saga_steps where transfer_id = ?::uuid", Integer.class, transferId);
    }

    private String statusOf(String transferId) {
        return jdbc.queryForObject("select status from transfers where id = ?::uuid", String.class, transferId);
    }

    private String storedRow(String transferId) {
        return jdbc.queryForObject("select t::text from transfers t where id = ?::uuid", String.class, transferId);
    }

    private void waitUntilReserved(String transferId) {
        await().atMost(Duration.ofSeconds(10)).until(() -> "SCREENING".equals(statusOf(transferId)));
    }

    @Test
    @Tag("M4-S06")
    @DisplayName("The same send repeated with its key gets the first transfer back")
    void theSameSendRepeatedWithItsKeyGetsTheFirstTransferBack() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = send(sender, key, eur80(RECIPIENT));
        String transferId = (String) json(first).get("id");
        waitUntilReserved(transferId);
        int outboxRows = outboxRowsOf(transferId);
        int sagaSteps = sagaStepsOf(transferId);

        HttpResponse<String> second = send(sender, key, eur80(RECIPIENT));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        Map<?, ?> secondBody = json(second);
        assertThat(secondBody.get("id")).isEqualTo(transferId);
        assertThat(secondBody.get("senderId")).isEqualTo(sender.toString());
        assertThat(secondBody.get("recipientId")).isEqualTo(RECIPIENT.toString());
        assertThat(secondBody.get("amount")).isEqualTo(Map.of("currency", "EUR", "amount", "80.0000"));
        assertThat(secondBody.get("note")).isEqualTo("pizza");
        assertThat(transferIdsOf(sender)).containsExactly(transferId);
        assertThat(outboxRowsOf(transferId)).isEqualTo(outboxRows);
        assertThat(sagaStepsOf(transferId)).isEqualTo(sagaSteps);
    }

    @Test
    @DisplayName("A repeat after the transfer was posted gets the transfer as it is now")
    void aRepeatAfterTheTransferWasPostedGetsTheTransferAsItIsNow() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String transferId = (String) json(send(sender, key, eur80(RECIPIENT))).get("id");
        await().atMost(Duration.ofSeconds(10)).until(() -> "SCREENING".equals(statusOf(transferId)));
        fraudResults.applyFraudResult(new FraudResult(
                UUID.randomUUID(),
                UUID.fromString(transferId),
                FraudDecision.CLEARED,
                new BigDecimal("0.1000"),
                List.of()));
        await().atMost(Duration.ofSeconds(10)).until(() -> "POSTED".equals(statusOf(transferId)));

        HttpResponse<String> repeat = send(sender, key, eur80(RECIPIENT));

        assertThat(repeat.statusCode()).isEqualTo(202);
        Map<?, ?> body = json(repeat);
        assertThat(body.get("id")).isEqualTo(transferId);
        assertThat(body.get("status")).isEqualTo("POSTED");
    }

    @Test
    @Tag("M4-S08")
    @DisplayName("Different keys make different transfers")
    void differentKeysMakeDifferentTransfers() throws Exception {
        UUID sender = UUID.randomUUID();

        HttpResponse<String> first = send(sender, UUID.randomUUID(), eur80(RECIPIENT));
        HttpResponse<String> second = send(sender, UUID.randomUUID(), eur80(RECIPIENT));

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(json(first).get("id")).isNotEqualTo(json(second).get("id"));
        assertThat(transfersOf(sender)).isEqualTo(2);
    }

    @Test
    @Tag("M4-S09")
    @DisplayName("The same key from two users makes two transfers")
    void theSameKeyFromTwoUsersMakesTwoTransfers() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID otherSender = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        Map<?, ?> first = json(send(sender, key, eur80(RECIPIENT)));
        Map<?, ?> second = json(send(otherSender, key, eur80(RECIPIENT)));

        assertThat(first.get("senderId")).isEqualTo(sender.toString());
        assertThat(second.get("senderId")).isEqualTo(otherSender.toString());
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
        assertThat(transferIdsOf(sender)).containsExactly((String) first.get("id"));
        assertThat(transferIdsOf(otherSender)).containsExactly((String) second.get("id"));
    }

    @Test
    @Tag("M4-S10")
    @DisplayName("A send without a key is refused")
    void aSendWithoutAKeyIsRefused() throws Exception {
        UUID sender = UUID.randomUUID();

        HttpResponse<String> response = send(sender, null, eur80(RECIPIENT));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(transfersOf(sender)).isZero();
    }

    @Test
    @Tag("M4-S11")
    @DisplayName("A refused send gets the same refusal when repeated")
    void aRefusedSendGetsTheSameRefusalWhenRepeated() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String unknownRecipient = toHandle("ghost", "80.00", "EUR", "pizza");

        HttpResponse<String> first = send(sender, key, unknownRecipient);
        HttpResponse<String> second = send(sender, key, unknownRecipient);

        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(json(first).get("code")).isEqualTo("RECIPIENT_NOT_FOUND");
        assertThat(second.statusCode()).isEqualTo(422);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(transfersOf(sender)).isZero();
    }

    @Test
    @DisplayName("A send refused for an unknown handle leaves its key free for the same send to a known one")
    void aSendRefusedForAnUnknownHandleLeavesItsKeyFree() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        HttpResponse<String> refused = send(sender, key, toHandle("ghost", "80.00", "EUR", "pizza"));
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(json(refused).get("code")).isEqualTo("RECIPIENT_NOT_FOUND");
        assertThat(transfersOf(sender)).isZero();

        HttpResponse<String> retry = send(sender, key, toHandle("lina", "80.00", "EUR", "pizza"));

        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(transferIdsOf(sender)).containsExactly((String) json(retry).get("id"));
    }

    @Test
    @Tag("M4-S12")
    @DisplayName("A send the transfer service could not take goes through when repeated")
    void aSendTheTransferServiceCouldNotTakeGoesThroughWhenRepeated() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String body = toHandle("lina", "80.00", "EUR", "pizza");
        when(accounts.lookupByHandle("lina"))
                .thenThrow(new LedgerUnavailableException(new RuntimeException("down")))
                .thenReturn(Optional.of(RECIPIENT));

        HttpResponse<String> first = send(sender, key, body);
        HttpResponse<String> second = send(sender, key, body);

        assertThat(first.statusCode()).isEqualTo(503);
        assertThat(json(first).get("code")).isEqualTo("LEDGER_UNAVAILABLE");
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(transferIdsOf(sender)).containsExactly((String) json(second).get("id"));
    }

    private enum Difference {
        ANOTHER_AMOUNT(toUser(RECIPIENT, "80.01", "EUR", "pizza")),
        ANOTHER_RECIPIENT(toUser(SendIdempotencyTest.ANOTHER_RECIPIENT, "80.00", "EUR", "pizza")),
        ANOTHER_NOTE(toUser(RECIPIENT, "80.00", "EUR", "sushi")),
        NO_NOTE(toUser(RECIPIENT, "80.00", "EUR", null)),
        ANOTHER_CURRENCY(toUser(RECIPIENT, "80.00", "USD", "pizza"));

        private final String body;

        Difference(String body) {
            this.body = body;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Difference.class)
    @Tag("M4-S39")
    @DisplayName("A key reused for a different transfer is refused")
    void aKeyReusedForADifferentTransferIsRefused(Difference difference) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String transferId = (String) json(send(sender, key, eur80(RECIPIENT))).get("id");
        waitUntilReserved(transferId);
        String rowBefore = storedRow(transferId);
        int outboxRows = outboxRowsOf(transferId);
        int sagaSteps = sagaStepsOf(transferId);

        HttpResponse<String> response = send(sender, key, difference.body);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        Map<?, ?> problem = json(response);
        assertThat(problem.get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(problem.get("detail"))
                .isEqualTo("idempotency key " + key + " was already used for a different transfer");
        assertThat(transferIdsOf(sender)).containsExactly(transferId);
        assertThat(storedRow(transferId)).isEqualTo(rowBefore);
        assertThat(outboxRowsOf(transferId)).isEqualTo(outboxRows);
        assertThat(sagaStepsOf(transferId)).isEqualTo(sagaSteps);
    }

    @Test
    @DisplayName("A recipient named by handle first and by id in the repeat is the same request")
    void aRecipientNamedByHandleThenByIdIsTheSameRequest() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String transferId = (String) json(send(sender, key, toHandle("lina", "80.00", "EUR", "pizza")))
                .get("id");

        HttpResponse<String> repeat = send(sender, key, eur80(RECIPIENT));

        assertThat(repeat.statusCode()).isEqualTo(202);
        assertThat(json(repeat).get("id")).isEqualTo(transferId);
        assertThat(transferIdsOf(sender)).containsExactly(transferId);
    }

    @Test
    @DisplayName("Two concurrent sends with one key and different amounts make one transfer")
    void twoConcurrentSendsWithOneKeyAndDifferentAmountsMakeOneTransfer() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID reference = UUID.randomUUID();
        String referenceId = (String)
                json(send(UUID.randomUUID(), reference, eur80(RECIPIENT))).get("id");
        waitUntilReserved(referenceId);
        int singleSendOutboxRows = outboxRowsOf(referenceId);
        int singleSendSagaSteps = sagaStepsOf(referenceId);
        CountDownLatch start = new CountDownLatch(1);
        List<HttpResponse<String>> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> sends = List.of("80.00", "90.00").stream()
                    .map(amount -> pool.submit(() -> {
                        start.await();
                        return send(sender, key, toUser(RECIPIENT, amount, "EUR", "pizza"));
                    }))
                    .toList();
            start.countDown();
            responses = List.of(sends.get(0).get(), sends.get(1).get());
        }

        List<HttpResponse<String>> accepted =
                responses.stream().filter(r -> r.statusCode() == 202).toList();
        List<HttpResponse<String>> refused =
                responses.stream().filter(r -> r.statusCode() == 422).toList();
        assertThat(accepted).hasSize(1);
        assertThat(refused).hasSize(1);
        Map<?, ?> problem = json(refused.get(0));
        assertThat(problem.get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(problem.get("detail"))
                .isEqualTo("idempotency key " + key + " was already used for a different transfer");
        String transferId = (String) json(accepted.get(0)).get("id");
        assertThat(transferIdsOf(sender)).containsExactly(transferId);
        waitUntilReserved(transferId);
        assertThat(outboxRowsOf(transferId)).isEqualTo(singleSendOutboxRows);
        assertThat(sagaStepsOf(transferId)).isEqualTo(singleSendSagaSteps);
    }
}
