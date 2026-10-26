package dev.treyer.sagapay.fraud.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.fraud.application.port.out.FraudCasePort;
import dev.treyer.sagapay.fraud.application.port.out.OutboxPort;
import dev.treyer.sagapay.fraud.application.port.out.ProcessedEventPort;
import dev.treyer.sagapay.fraud.application.port.out.SenderHistoryPort;
import dev.treyer.sagapay.fraud.domain.FraudCase;
import dev.treyer.sagapay.fraud.domain.FraudRule;
import dev.treyer.sagapay.fraud.domain.FraudRuleSettings;
import dev.treyer.sagapay.fraud.domain.FraudScorer;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreeningServiceTest {

    private static final UUID SENDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KNOWN_RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEW_RECIPIENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TRANSFER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String EVENT_ID = "55555555-5555-5555-5555-555555555555";
    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30Z");

    private final List<String> calls = new ArrayList<>();
    private final List<FraudCase> openedCases = new ArrayList<>();
    private final List<OutboxMessage> savedMessages = new ArrayList<>();
    private SenderActivity history = new SenderActivity(1, Set.of(KNOWN_RECIPIENT));
    private boolean alreadyProcessed;
    private RuntimeException outboxFailure;

    private final SenderHistoryPort historyPort = new SenderHistoryPort() {
        @Override
        public SenderActivity read(UUID senderId) {
            calls.add("history.read " + senderId);
            return history;
        }

        @Override
        public void record(UUID senderId, UUID recipientId) {
            calls.add("history.record " + senderId + " " + recipientId);
        }
    };
    private final ProcessedEventPort processedPort = (eventId, consumer) -> {
        calls.add("processed " + eventId + " " + consumer);
        return !alreadyProcessed;
    };
    private final FraudCasePort casePort = fraudCase -> {
        calls.add("case.open");
        openedCases.add(fraudCase);
    };
    private final OutboxPort outboxPort = message -> {
        calls.add("outbox.save " + message.eventType());
        if (outboxFailure != null) {
            throw outboxFailure;
        }
        savedMessages.add(message);
    };

    private final ScreeningService service = new ScreeningService(
            historyPort,
            new FraudScorer(new FraudRuleSettings(
                    new BigDecimal("0.40"),
                    5,
                    new BigDecimal("0.35"),
                    new BigDecimal("0.35"),
                    new BigDecimal("500"),
                    new BigDecimal("0.70"),
                    "rules-v1")),
            new DecisionWriter(
                    processedPort,
                    casePort,
                    outboxPort,
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC)));

    private static ReservedTransfer reserved(UUID recipient, String amount) {
        return new ReservedTransfer(EVENT_ID, TRANSFER, SENDER, recipient, Money.of(amount, "EUR"));
    }

    @Test
    void aClearedTransferReadsTheHistoryStoresTheResultThenUpdatesTheHistory() {
        service.screen(reserved(KNOWN_RECIPIENT, "20.00"));

        assertThat(calls)
                .containsExactly(
                        "history.read " + SENDER,
                        "processed " + EVENT_ID + " fraud-screening",
                        "outbox.save TransferCleared",
                        "history.record " + SENDER + " " + KNOWN_RECIPIENT);
    }

    @Test
    void aFlaggedTransferOpensItsCaseBeforeTheResultAndUpdatesTheHistoryLast() {
        service.screen(reserved(NEW_RECIPIENT, "500.00"));

        assertThat(calls)
                .containsExactly(
                        "history.read " + SENDER,
                        "processed " + EVENT_ID + " fraud-screening",
                        "case.open",
                        "outbox.save TransferFlagged",
                        "history.record " + SENDER + " " + NEW_RECIPIENT);
    }

    @Test
    void anEventAlreadyProcessedWritesNothingAndLeavesTheHistoryUnchanged() {
        alreadyProcessed = true;

        service.screen(reserved(NEW_RECIPIENT, "500.00"));

        assertThat(calls).containsExactly("history.read " + SENDER, "processed " + EVENT_ID + " fraud-screening");
    }

    @Test
    void aDecisionThatFailsToBeStoredLeavesTheHistoryUnchanged() {
        outboxFailure = new IllegalStateException("outbox unavailable");

        assertThatThrownBy(() -> service.screen(reserved(NEW_RECIPIENT, "500.00")))
                .isSameAs(outboxFailure);

        assertThat(calls)
                .containsExactly(
                        "history.read " + SENDER,
                        "processed " + EVENT_ID + " fraud-screening",
                        "case.open",
                        "outbox.save TransferFlagged");
    }

    @Test
    void aClearedTransferPublishesItsResultAndOpensNoCase() {
        service.screen(reserved(KNOWN_RECIPIENT, "20.00"));

        assertThat(openedCases).isEmpty();
        assertThat(savedMessages).hasSize(1);
        OutboxMessage message = savedMessages.getFirst();
        assertThat(message.aggregateType()).isEqualTo("Transfer");
        assertThat(message.aggregateId()).isEqualTo(TRANSFER.toString());
        assertThat(message.eventType()).isEqualTo("TransferCleared");
        assertThat(message.payload())
                .isEqualTo("{\"transferId\":\"" + TRANSFER + "\",\"senderId\":\"" + SENDER
                        + "\",\"decision\":\"CLEARED\",\"score\":0,\"reasons\":[],\"modelVersion\":\"rules-v1\","
                        + "\"ts\":\"2026-03-01T10:15:30Z\"}");
    }

    @Test
    void aFlaggedTransferOpensACaseWithItsScoreReasonsAndTheHistoryItRead() {
        history = new SenderActivity(3, Set.of(KNOWN_RECIPIENT));

        service.screen(reserved(NEW_RECIPIENT, "500.00"));

        assertThat(openedCases)
                .containsExactly(new FraudCase(
                        TRANSFER,
                        SENDER,
                        Money.of("500.00", "EUR"),
                        new BigDecimal("0.70"),
                        List.of(FraudRule.NEW_RECIPIENT, FraudRule.AMOUNT_ROUND),
                        new SenderActivity(3, Set.of(KNOWN_RECIPIENT))));
    }

    @Test
    void aFlaggedTransferPublishesAFlaggedResult() {
        service.screen(reserved(NEW_RECIPIENT, "500.00"));

        assertThat(savedMessages).hasSize(1);
        OutboxMessage message = savedMessages.getFirst();
        assertThat(message.eventType()).isEqualTo("TransferFlagged");
        assertThat(message.payload())
                .isEqualTo("{\"transferId\":\"" + TRANSFER + "\",\"senderId\":\"" + SENDER
                        + "\",\"decision\":\"FLAGGED\",\"score\":0.70,"
                        + "\"reasons\":[\"NEW_RECIPIENT\",\"AMOUNT_ROUND\"],\"modelVersion\":\"rules-v1\","
                        + "\"ts\":\"2026-03-01T10:15:30Z\"}");
    }

    @Test
    void theRulesSeeTheHistoryThatWasReadBeforeTheTransfer() {
        history = new SenderActivity(6, Set.of(KNOWN_RECIPIENT));

        service.screen(reserved(KNOWN_RECIPIENT, "20.00"));

        assertThat(savedMessages.getFirst().payload()).contains("\"score\":0.40,\"reasons\":[\"VELOCITY_1H\"]");
    }
}
