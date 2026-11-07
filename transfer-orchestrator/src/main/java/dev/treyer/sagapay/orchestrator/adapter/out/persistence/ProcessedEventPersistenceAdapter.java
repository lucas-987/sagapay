package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.application.port.out.ProcessedEventPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ProcessedEventPersistenceAdapter implements ProcessedEventPort {

    private final JdbcClient jdbc;

    ProcessedEventPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ON CONFLICT DO NOTHING rather than catching the duplicate key: a failed INSERT aborts
    // the surrounding PostgreSQL transaction.
    @Override
    public boolean tryMarkProcessed(UUID eventId, String consumer) {
        return jdbc.sql("insert into processed_events (event_id, consumer) values (:eventId, :consumer)"
                                + " on conflict (event_id) do nothing")
                        .param("eventId", eventId)
                        .param("consumer", consumer)
                        .update()
                > 0;
    }
}
