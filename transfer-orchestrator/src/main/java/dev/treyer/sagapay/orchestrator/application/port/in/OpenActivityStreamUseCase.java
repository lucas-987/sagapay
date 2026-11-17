package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

public interface OpenActivityStreamUseCase {

    /** Where the events of one connection go. */
    interface Sink {
        /** Never blocks: the sink holds what it cannot write yet, or ends its connection. The event
         * is already as the connection's user may see it. */
        void push(TransferEvent event);
    }

    /** The registration of one connection. */
    interface Connection {
        /** The connection ended: nothing is pushed to its sink any more. */
        void close();
    }

    Connection open(UUID userId, Sink sink);
}
