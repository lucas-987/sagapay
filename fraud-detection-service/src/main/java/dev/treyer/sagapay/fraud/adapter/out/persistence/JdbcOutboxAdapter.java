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

    // Each service has its own database, so this only has to differ from its other advisory locks.
    private static final long PUBLISHER_LOCK = 1;

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
    public boolean tryLockPublisher() {
        return jdbc.sql("select pg_try_advisory_xact_lock(:lockId)")
                .param("lockId", PUBLISHER_LOCK)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<OutboxMessage> findPublishable(int limit) {
        return jdbc.sql("""
                        select o.id, o.aggregate_type, o.aggregate_id, o.event_type, o.payload::text as payload
                        from outbox o
                        where o.published_at is null
                          and not exists (select 1 from outbox_quarantine q where q.aggregate_id = o.aggregate_id)
                        order by o.created_at, o.id
                        limit :limit
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

    @Override
    public void quarantine(UUID aggregateId, UUID outboxId, String reason) {
        jdbc.sql("""
                        insert into outbox_quarantine (aggregate_id, outbox_id, reason)
                        values (:aggregateId, :outboxId, :reason)
                        on conflict (aggregate_id) do nothing
                        """)
                .param("aggregateId", aggregateId)
                .param("outboxId", outboxId)
                .param("reason", reason)
                .update();
    }

    @Override
    public double oldestPublishableAgeSeconds() {
        return jdbc.sql("""
                        select coalesce(extract(epoch from now() - min(o.created_at)), 0) from outbox o
                        where o.published_at is null
                          and not exists (select 1 from outbox_quarantine q where q.aggregate_id = o.aggregate_id)
                        """).query(Double.class).single();
    }

    @Override
    public long countQuarantinedKeys() {
        return jdbc.sql("select count(*) from outbox_quarantine")
                .query(Long.class)
                .single();
    }
}
