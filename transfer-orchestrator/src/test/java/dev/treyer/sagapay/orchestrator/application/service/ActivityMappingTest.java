package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;
import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent.Outcome;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityMappingTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRANSFER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final BigDecimal AMOUNT = new BigDecimal("80.0000");

    private static TransferEvent event(Outcome outcome, UUID sender, UUID recipient, FailureReason reason) {
        return new TransferEvent(EVENT_ID, outcome, TRANSFER_ID, sender, recipient, AMOUNT, "EUR", reason);
    }

    private static TransferEvent pushed(Outcome outcome, UUID recipient, FailureReason cause) {
        return new TransferEvent(EVENT_ID, outcome, TRANSFER_ID, SENDER, recipient, AMOUNT, "EUR", cause);
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {"POSTED", "BLOCKED"})
    @DisplayName("A posted or blocked transfer is pushed to its sender and its recipient")
    void aPostedOrBlockedTransferIsPushedToBothParties(Outcome outcome) {
        List<ActivityMapping.Delivery> deliveries = ActivityMapping.of(event(outcome, SENDER, RECIPIENT, null));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed(outcome, RECIPIENT, null)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed(outcome, RECIPIENT, null)));
    }

    @Test
    @DisplayName("A failed transfer is pushed with its cause to the sender and without it to the recipient")
    void aFailedTransferCarriesItsCauseToTheSenderOnly() {
        List<ActivityMapping.Delivery> deliveries =
                ActivityMapping.of(event(Outcome.FAILED, SENDER, RECIPIENT, FailureReason.INSUFFICIENT_FUNDS));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(
                                SENDER, pushed(Outcome.FAILED, RECIPIENT, FailureReason.INSUFFICIENT_FUNDS)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed(Outcome.FAILED, RECIPIENT, null)));
    }

    @Test
    @DisplayName("A failure published without a reason is pushed without a cause to both parties")
    void aFailureWithoutAReasonHasNoCause() {
        List<ActivityMapping.Delivery> deliveries = ActivityMapping.of(event(Outcome.FAILED, SENDER, RECIPIENT, null));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed(Outcome.FAILED, RECIPIENT, null)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed(Outcome.FAILED, RECIPIENT, null)));
    }

    @Test
    @DisplayName("A transfer between a user and themselves is pushed once, with the cause")
    void aTransferToSelfIsPushedOnceWithTheCause() {
        assertThat(ActivityMapping.of(event(Outcome.FAILED, SENDER, SENDER, FailureReason.POST_REJECTED)))
                .containsExactly(new ActivityMapping.Delivery(
                        SENDER, pushed(Outcome.FAILED, SENDER, FailureReason.POST_REJECTED)));
    }

    @Test
    @DisplayName("A posted transfer between a user and themselves is pushed once")
    void aPostedTransferToSelfIsPushedOnce() {
        assertThat(ActivityMapping.of(event(Outcome.POSTED, SENDER, SENDER, null)))
                .containsExactly(new ActivityMapping.Delivery(SENDER, pushed(Outcome.POSTED, SENDER, null)));
    }
}
