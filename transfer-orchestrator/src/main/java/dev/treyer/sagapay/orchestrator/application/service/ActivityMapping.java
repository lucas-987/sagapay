package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;

import java.util.List;
import java.util.UUID;

/** Which user is pushed what for each published event: each party once, as they may see it. */
final class ActivityMapping {

    record Delivery(UUID userId, TransferEvent event) {}

    private ActivityMapping() {}

    static List<Delivery> of(TransferEvent event) {
        return parties(event).stream()
                .map(userId -> new Delivery(userId, event.visibleTo(userId)))
                .toList();
    }

    private static List<UUID> parties(TransferEvent event) {
        return event.senderId().equals(event.recipientId())
                ? List.of(event.senderId())
                : List.of(event.senderId(), event.recipientId());
    }
}
