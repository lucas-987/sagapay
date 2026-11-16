package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.SenderOnlyDetails;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;

import java.util.List;
import java.util.UUID;

/** Which user is pushed what for each published event: each party once, as they may see it. */
final class ActivityMapping {

    record Delivery(UUID userId, ActivityEvent event) {}

    private ActivityMapping() {}

    static List<Delivery> of(TransferEvent event) {
        return switch (event.outcome()) {
            case POSTED, BLOCKED, FAILED ->
                parties(event).stream()
                        .map(userId -> new Delivery(userId, activityEventVisibleTo(event, userId)))
                        .toList();
            default -> List.of();
        };
    }

    private static List<UUID> parties(TransferEvent event) {
        return event.senderId().equals(event.recipientId())
                ? List.of(event.senderId())
                : List.of(event.senderId(), event.recipientId());
    }

    private static ActivityEvent activityEventVisibleTo(TransferEvent event, UUID viewer) {
        return new ActivityEvent(
                event.eventId(),
                event.outcome(),
                event.transferId(),
                event.senderId(),
                event.recipientId(),
                event.amount(),
                event.currency(),
                SenderOnlyDetails.failureReason(event.reason(), event.senderId(), viewer));
    }
}
