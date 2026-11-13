package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;

import java.io.IOException;
import java.util.UUID;

public interface OpenActivityStreamUseCase {

    /** Where the events of one connection are written. {@code send} may block on a slow client. */
    interface Sink {
        void send(ActivityEvent event) throws IOException;

        /** Ends the connection from the server side. */
        void close();
    }

    /** The registration of one connection. */
    interface Connection {
        /** The client left or the stream ended: nothing is pushed to the sink any more. */
        void close();
    }

    Connection open(UUID userId, Sink sink);
}
