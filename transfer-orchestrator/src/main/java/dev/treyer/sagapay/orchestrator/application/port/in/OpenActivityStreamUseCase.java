package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;

import java.util.UUID;

public interface OpenActivityStreamUseCase {

    /** Where the events of one connection go. */
    interface Sink {
        /** Never blocks: the sink holds what it cannot write yet, or ends its connection. */
        void push(ActivityEvent event);
    }

    /** The registration of one connection. */
    interface Connection {
        /** The connection ended: nothing is pushed to its sink any more. */
        void close();
    }

    Connection open(UUID userId, Sink sink);
}
