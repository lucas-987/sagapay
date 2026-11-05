package dev.treyer.sagapay.fraud.adapter.out.persistence;

import dev.treyer.sagapay.fraud.application.port.out.OutboxPort;
import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class JdbcOutboxAdapter implements OutboxPort, OutboxRelayPort {

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

    @Override
    public List<OutboxMessage> findUnpublishedForUpdateSkipLocked(int limit) {
        return jdbc.sql("""
                        select id, aggregate_type, aggregate_id, event_type, payload::text as payload
                        from outbox
                        where published_at is null
                        order by created_at
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", limit)
                .query((rs, row) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    @Override
    public void markPublished(UUID id) {
        jdbc.sql("update outbox set published_at = now() where id = :id")
                .param("id", id)
                .update();
    }
}
