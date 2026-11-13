package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityMappingTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRANSFER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static TransferEvent event(String type, UUID sender, UUID recipient, String reason) {
        return new TransferEvent(EVENT_ID, type, TRANSFER_ID, sender, recipient, "80.0000", "EUR", reason);
    }

    private static ActivityEvent pushed(String name, String cause) {
        return new ActivityEvent(EVENT_ID.toString(), name, TRANSFER_ID, SENDER, RECIPIENT, "80.0000", "EUR", cause);
    }

    @Test
    @DisplayName("A posted transfer is pushed to its sender and its recipient")
    void aPostedTransferIsPushedToBothParties() {
        List<ActivityMapping.Delivery> deliveries =
                ActivityMapping.of(event("TransferPosted.v1", SENDER, RECIPIENT, null));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed("transfer.posted", null)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed("transfer.posted", null)));
    }

    @Test
    @DisplayName("A blocked transfer is pushed to its sender and its recipient")
    void aBlockedTransferIsPushedToBothParties() {
        List<ActivityMapping.Delivery> deliveries =
                ActivityMapping.of(event("TransferBlocked.v1", SENDER, RECIPIENT, null));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed("transfer.blocked", null)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed("transfer.blocked", null)));
    }

    @Test
    @DisplayName("A failed transfer is pushed with its cause to the sender and without it to the recipient")
    void aFailedTransferCarriesItsCauseToTheSenderOnly() {
        List<ActivityMapping.Delivery> deliveries =
                ActivityMapping.of(event("TransferFailed.v1", SENDER, RECIPIENT, "INSUFFICIENT_FUNDS"));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed("transfer.failed", "INSUFFICIENT_FUNDS")),
                        new ActivityMapping.Delivery(RECIPIENT, pushed("transfer.failed", null)));
    }

    @Test
    @DisplayName("A failure published without a reason is pushed without a cause to both parties")
    void aFailureWithoutAReasonHasNoCause() {
        List<ActivityMapping.Delivery> deliveries =
                ActivityMapping.of(event("TransferFailed.v1", SENDER, RECIPIENT, null));

        assertThat(deliveries)
                .containsExactly(
                        new ActivityMapping.Delivery(SENDER, pushed("transfer.failed", null)),
                        new ActivityMapping.Delivery(RECIPIENT, pushed("transfer.failed", null)));
    }

    @Test
    @DisplayName("A transfer between a user and themselves is pushed once, with the cause")
    void aTransferToSelfIsPushedOnceWithTheCause() {
        ActivityEvent expected = new ActivityEvent(
                EVENT_ID.toString(), "transfer.failed", TRANSFER_ID, SENDER, SENDER, "80.0000", "EUR", "POST_REJECTED");

        assertThat(ActivityMapping.of(event("TransferFailed.v1", SENDER, SENDER, "POST_REJECTED")))
                .containsExactly(new ActivityMapping.Delivery(SENDER, expected));
    }

    @Test
    @DisplayName("A posted transfer between a user and themselves is pushed once")
    void aPostedTransferToSelfIsPushedOnce() {
        ActivityEvent expected = new ActivityEvent(
                EVENT_ID.toString(), "transfer.posted", TRANSFER_ID, SENDER, SENDER, "80.0000", "EUR", null);

        assertThat(ActivityMapping.of(event("TransferPosted.v1", SENDER, SENDER, null)))
                .containsExactly(new ActivityMapping.Delivery(SENDER, expected));
    }

    @ParameterizedTest(name = "{0} is pushed to nobody")
    @ValueSource(
            strings = {
                "TransferInitiated.v1",
                "FundsReserved.v1",
                "TransferTeleported.v1",
                "TransferPosted.v2",
                "TransferPosted",
                ""
            })
    @DisplayName("An event with no stream event, an unknown type or another version is ignored")
    void anEventWithNoStreamEventIsIgnored(String type) {
        assertThat(ActivityMapping.of(event(type, SENDER, RECIPIENT, null))).isEmpty();
    }
}
