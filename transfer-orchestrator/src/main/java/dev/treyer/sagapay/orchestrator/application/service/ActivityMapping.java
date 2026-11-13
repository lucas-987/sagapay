package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;

import java.util.List;
import java.util.UUID;

/**
 * Which user is pushed what for each published event. The cause of a failure could reveal the
 * sender's balance, so only the sender is given it; a user who is both parties is the sender.
 */
final class ActivityMapping {

    record Delivery(UUID userId, ActivityEvent event) {}

    private ActivityMapping() {}

    static List<Delivery> of(TransferEvent event) {
        return switch (event.type()) {
            case "TransferPosted.v1" -> bothParties(event, "transfer.posted", null);
            case "TransferBlocked.v1" -> bothParties(event, "transfer.blocked", null);
            case "TransferFailed.v1" -> bothParties(event, "transfer.failed", event.reason());
            default -> List.of();
        };
    }

    private static List<Delivery> bothParties(TransferEvent event, String name, String senderCause) {
        Delivery sender = new Delivery(event.senderId(), activity(event, name, senderCause));
        if (event.senderId().equals(event.recipientId())) {
            return List.of(sender);
        }
        return List.of(sender, new Delivery(event.recipientId(), activity(event, name, null)));
    }

    private static ActivityEvent activity(TransferEvent event, String name, String cause) {
        return new ActivityEvent(
                event.eventId().toString(),
                name,
                event.transferId(),
                event.senderId(),
                event.recipientId(),
                event.amount(),
                event.currency(),
                cause);
    }
}
