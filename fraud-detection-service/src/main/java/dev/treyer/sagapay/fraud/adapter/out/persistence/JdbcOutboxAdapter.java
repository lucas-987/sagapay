package dev.treyer.sagapay.fraud.adapter.out.persistence;

import dev.treyer.sagapay.fraud.application.port.out.OutboxPort;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcOutboxAdapter implements OutboxPort {

    private final JdbcClient jdbc;

    JdbcOutboxAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(OutboxMessage message) {
        jdbc.sql("""
                        insert into outbox (id, aggregate_type, aggregate_id, event_type, payload)
                        values (:id, :aggregateType, :aggregateId, :eventType, cast(:payload as jsonb))
                        """)
                .param("id", message.id())
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("eventType", message.eventType())
                .param("payload", message.payload())
                .update();
    }
}
