package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Real port and real PostgreSQL: the caller is the subject of a token signed with the test key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class FailureCauseVisibilityTest {

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

    @MockitoBean
    private AccountLookupPort accounts;

    private enum Party {
        SENDER,
        RECIPIENT
    }

    private enum How {
        BY_ITS_ID,
        IN_THEIR_TRANSFERS
    }

    static Stream<Arguments> failureCauseRows() {
        return Stream.of(
                Arguments.argumentSet("sender by its id", Party.SENDER, How.BY_ITS_ID, "INSUFFICIENT_FUNDS"),
                Arguments.argumentSet(
                        "sender in their transfers", Party.SENDER, How.IN_THEIR_TRANSFERS, "INSUFFICIENT_FUNDS"),
                Arguments.argumentSet("recipient by its id", Party.RECIPIENT, How.BY_ITS_ID, null),
                Arguments.argumentSet("recipient in their transfers", Party.RECIPIENT, How.IN_THEIR_TRANSFERS, null));
    }

    private UUID failedForInsufficientFunds(UUID sender, UUID recipient) throws Exception {
        when(ledger.checkAndReserve(anyString(), any(), any(), any(Money.class)))
                .thenReturn(new ReservationResult.InsufficientFunds());
        UUID transferId = send(sender, recipient);
        awaitStatus(transferId, "FAILED");
        return transferId;
    }

    private UUID posted(UUID sender, UUID recipient) throws Exception {
        when(ledger.checkAndReserve(anyString(), any(), any(), any(Money.class)))
                .thenReturn(new ReservationResult.Ok(
                        UUID.randomUUID(), Instant.now().plusSeconds(600)));
        UUID transferId = send(sender, recipient);
        awaitStatus(transferId, "SCREENING");
        fraudResults.applyFraudResult(new FraudResult(
                UUID.randomUUID(), transferId, FraudDecision.CLEARED, new BigDecimal("0.1000"), List.of()));
        awaitStatus(transferId, "POSTED");
        return transferId;
    }

    private UUID send(UUID sender, UUID recipient) throws Exception {
        return UUID.fromString(
                (String) postTransfer(sender, recipient, UUID.randomUUID()).get("id"));
    }

    private Map<?, ?> postTransfer(UUID sender, UUID recipient, UUID idempotencyKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/transfers"))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "80.00"}}""".formatted(recipient)))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("Authorization", "Bearer " + TestTokens.forSubject(sender))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return JSON.readValue(response.body(), Map.class);
    }

    private void awaitStatus(UUID transferId, String status) {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> status.equals(
                        jdbc.queryForObject("select status from transfers where id = ?", String.class, transferId)));
    }

    private Map<?, ?> read(UUID reader, UUID transferId) throws Exception {
        return get(reader, "/v1/transfers/" + transferId);
    }

    private List<Map<?, ?>> list(UUID reader, String query) throws Exception {
        return itemsOf(get(reader, "/v1/transfers" + query));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> itemsOf(Map<?, ?> page) {
        return (List<Map<?, ?>>) page.get("items");
    }

    private Map<?, ?> get(UUID reader, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TestTokens.forSubject(reader))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readValue(response.body(), Map.class);
    }

    private static Map<?, ?> theOne(List<Map<?, ?>> items, UUID transferId) {
        List<Map<?, ?>> matching = items.stream()
                .filter(item -> transferId.toString().equals(item.get("id")))
                .toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private Map<?, ?> readAs(Party party, How how, UUID sender, UUID recipient, UUID transferId) throws Exception {
        UUID reader = party == Party.SENDER ? sender : recipient;
        return how == How.BY_ITS_ID ? read(reader, transferId) : theOne(list(reader, ""), transferId);
    }

    @ParameterizedTest
    @MethodSource("failureCauseRows")
    @Tag("M4-S40")
    @DisplayName("Reading a failed transfer shows its cause to the sender only")
    void readingAFailedTransferShowsItsCauseToTheSenderOnly(Party party, How how, String cause) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transferId = failedForInsufficientFunds(sender, recipient);

        Map<?, ?> seen = readAs(party, how, sender, recipient, transferId);

        assertThat(seen.get("status")).isEqualTo("FAILED");
        assertThat(seen.get("failureReason")).isEqualTo(cause);
    }

    @ParameterizedTest
    @EnumSource(Party.class)
    @Tag("M4-S41")
    @DisplayName("The steps of a transfer are shown to its sender only")
    void theStepsOfATransferAreShownToItsSenderOnly(Party party) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transferId = failedForInsufficientFunds(sender, recipient);

        Map<?, ?> seen = read(party == Party.SENDER ? sender : recipient, transferId);

        assertThat(seen.get("status")).isEqualTo("FAILED");
        List<Map<?, ?>> expectedSteps =
                party == Party.SENDER ? List.of(Map.of("step", "RESERVE", "outcome", "FAILED")) : List.of();
        assertThat(stepsOf(seen)).isEqualTo(expectedSteps);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> stepsOf(Map<?, ?> transfer) {
        return ((List<Map<String, Object>>) transfer.get("steps"))
                .stream()
                        .<Map<?, ?>>map(step -> Map.of("step", step.get("step"), "outcome", step.get("outcome")))
                        .toList();
    }

    @Test
    @DisplayName(
            "A list holding a failed transfer the caller sent and one they received shows the cause on the first only")
    void aMixedListShowsTheCauseOnTheSentTransferOnly() throws Exception {
        UUID caller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID sent = failedForInsufficientFunds(caller, other);
        UUID received = failedForInsufficientFunds(other, caller);

        List<Map<?, ?>> items = list(caller, "");

        assertThat(theOne(items, sent).get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(theOne(items, received).get("failureReason")).isNull();
    }

    @Test
    @DisplayName("The recipient's list of received transfers holds the failed transfer with no cause")
    void theRecipientsReceivedListHoldsTheFailedTransferWithNoCause() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transferId = failedForInsufficientFunds(sender, recipient);

        Map<?, ?> item = theOne(list(recipient, "?direction=RECEIVED"), transferId);

        assertThat(item.get("status")).isEqualTo("FAILED");
        assertThat(item.get("failureReason")).isNull();
    }

    @Test
    @Tag("M4-S42")
    @DisplayName("The cause and the steps of a transfer sent to oneself are shown to its user")
    void theCauseAndTheStepsOfATransferSentToOneselfAreShownToItsUser() throws Exception {
        UUID user = UUID.randomUUID();
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
        try (ActivityStreamSupport.Stream stream = new ActivityStreamSupport.Stream(http, port, user)) {
            UUID transferId = failedForInsufficientFunds(user, user);

            Map<?, ?> byId = read(user, transferId);
            Map<?, ?> inList = theOne(list(user, ""), transferId);

            assertThat(byId.get("status")).isEqualTo("FAILED");
            assertThat(inList.get("status")).isEqualTo("FAILED");
            assertThat(byId.get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(inList.get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(stepsOf(byId)).isEqualTo(List.of(Map.of("step", "RESERVE", "outcome", "FAILED")));
            // The event id is generated by the outbox: the name and the data are compared.
            UUID next = failedForInsufficientFunds(user, user);
            List<ActivityStreamSupport.Event> events = stream.take(2);
            assertThat(events)
                    .extracting(ActivityStreamSupport.Event::name, ActivityStreamSupport.Event::data)
                    .containsExactly(
                            tuple(
                                    "transfer.failed",
                                    ActivityStreamSupport.dataWithCause(transferId, user, user, "INSUFFICIENT_FUNDS")),
                            tuple(
                                    "transfer.failed",
                                    ActivityStreamSupport.dataWithCause(next, user, user, "INSUFFICIENT_FUNDS")));
        }
    }

    @ParameterizedTest
    @EnumSource(Party.class)
    @DisplayName("A posted transfer shows its steps to the sender, none to the recipient, and no cause to either")
    void aPostedTransferShowsItsStepsToTheSenderOnly(Party party) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transferId = posted(sender, recipient);

        Map<?, ?> seen = read(party == Party.SENDER ? sender : recipient, transferId);

        assertThat(seen.get("status")).isEqualTo("POSTED");
        assertThat(seen.get("failureReason")).isNull();
        List<Map<?, ?>> expectedSteps = party == Party.SENDER
                ? List.of(
                        Map.of("step", "RESERVE", "outcome", "OK"),
                        Map.of("step", "SCREEN", "outcome", "RETRY"),
                        Map.of("step", "SCREEN", "outcome", "OK"),
                        Map.of("step", "POST", "outcome", "OK"))
                : List.of();
        assertThat(stepsOf(seen)).isEqualTo(expectedSteps);
    }

    @Test
    @DisplayName("Replaying a send whose transfer has failed answers the sender with the cause")
    void replayingASendAnswersTheSenderWithTheCause() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        when(ledger.checkAndReserve(anyString(), any(), any(), any(Money.class)))
                .thenReturn(new ReservationResult.InsufficientFunds());
        UUID transferId =
                UUID.fromString((String) postTransfer(sender, recipient, key).get("id"));
        awaitStatus(transferId, "FAILED");

        Map<?, ?> replayed = postTransfer(sender, recipient, key);

        assertThat(replayed.get("id")).isEqualTo(transferId.toString());
        assertThat(replayed.get("status")).isEqualTo("FAILED");
        assertThat(replayed.get("failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
