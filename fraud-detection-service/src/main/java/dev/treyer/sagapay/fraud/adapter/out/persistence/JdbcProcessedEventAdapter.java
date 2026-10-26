package dev.treyer.sagapay.fraud.adapter.out.persistence;

import dev.treyer.sagapay.fraud.application.port.out.ProcessedEventPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcProcessedEventAdapter implements ProcessedEventPort {

    private final JdbcClient jdbc;

    JdbcProcessedEventAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ON CONFLICT DO NOTHING rather than catching the duplicate key: a failed INSERT aborts
    // the surrounding PostgreSQL transaction.
    @Override
    public boolean markProcessed(String eventId, String consumer) {
        return jdbc.sql("insert into processed_events (event_id, consumer) values (:eventId, :consumer)"
                                + " on conflict (event_id) do nothing")
                        .param("eventId", eventId)
                        .param("consumer", consumer)
                        .update()
                > 0;
    }
}
