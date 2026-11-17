package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.RelayTransferEventUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The subscribers of this instance, per user. A sink never blocks, so relaying an event never
 * waits on a client.
 */
public class ActivityStreamService implements OpenActivityStreamUseCase, RelayTransferEventUseCase {

    private final Map<UUID, Set<Sink>> subscribers = new ConcurrentHashMap<>();

    @Override
    public Connection open(UUID userId, Sink sink) {
        subscribe(userId, sink);
        return new Connection() {
            @Override
            public void close() {
                unsubscribe(userId, sink);
            }
        };
    }

    @Override
    public void relay(TransferEvent event) {
        for (ActivityMapping.Delivery delivery : ActivityMapping.of(event)) {
            for (Sink sink : subscribers.getOrDefault(delivery.userId(), Set.of())) {
                sink.push(delivery.event());
            }
        }
    }

    private void subscribe(UUID userId, Sink sink) {
        // Added inside compute: a concurrent unsubscribe of the user's last sink could otherwise drop
        // the set between looking it up and adding to it, and this sink would never be pushed anything.
        subscribers.compute(userId, (user, sinks) -> {
            Set<Sink> updated = sinks == null ? ConcurrentHashMap.newKeySet() : sinks;
            updated.add(sink);
            return updated;
        });
    }

    private void unsubscribe(UUID userId, Sink sink) {
        subscribers.computeIfPresent(userId, (user, sinks) -> {
            sinks.remove(sink);
            return sinks.isEmpty() ? null : sinks;
        });
    }
}
