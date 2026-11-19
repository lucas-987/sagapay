package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.SenderOnlyDetails;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer reached an outcome its parties are pushed. {@code reason} is only present on a
 * failure, {@code confirmationDeadline} on a block written since the field exists; {@link
 * #visibleTo} gives the event as one party may see it.
 */
public record TransferEvent(
        UUID eventId,
        Outcome outcome,
        UUID transferId,
        UUID senderId,
        UUID recipientId,
        BigDecimal amount,
        String currency,
        FailureReason reason,
        Instant confirmationDeadline) {

    /** The outcomes pushed to the parties; the other events of a transfer are not. */
    public enum Outcome {
        POSTED,
        BLOCKED,
        FAILED
    }

    /** The same event, without what only the sender is shown when {@code viewer} is not the sender. */
    public TransferEvent visibleTo(UUID viewer) {
        FailureReason shown = SenderOnlyDetails.failureReason(reason, senderId, viewer);
        Instant deadline = SenderOnlyDetails.confirmationDeadline(confirmationDeadline, senderId, viewer);
        return new TransferEvent(
                eventId, outcome, transferId, senderId, recipientId, amount, currency, shown, deadline);
    }
}
