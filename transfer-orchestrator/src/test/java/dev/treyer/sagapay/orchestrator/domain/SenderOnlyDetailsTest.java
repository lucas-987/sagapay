package dev.treyer.sagapay.orchestrator.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SenderOnlyDetailsTest {

    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    @DisplayName("The sender is shown the failure reason and the steps")
    void theSenderIsShownTheDetails() {
        assertThat(SenderOnlyDetails.failureReason(FailureReason.INSUFFICIENT_FUNDS, SENDER, SENDER))
                .isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(SenderOnlyDetails.steps(List.of("RESERVE"), SENDER, SENDER)).containsExactly("RESERVE");
    }

    @Test
    @DisplayName("Anyone but the sender is shown no failure reason and no step")
    void anyoneButTheSenderIsShownNothing() {
        assertThat(SenderOnlyDetails.failureReason(FailureReason.INSUFFICIENT_FUNDS, SENDER, RECIPIENT))
                .isNull();
        assertThat(SenderOnlyDetails.steps(List.of("RESERVE"), SENDER, RECIPIENT))
                .isEmpty();
    }
}
