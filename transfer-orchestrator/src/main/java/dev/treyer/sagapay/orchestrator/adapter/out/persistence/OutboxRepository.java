package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxRow, UUID> {

    List<OutboxRow> findByAggregateIdOrderByCreatedAt(UUID aggregateId);

    @Query(nativeQuery = true, value = "select pg_try_advisory_xact_lock(:lockId)")
    boolean tryAdvisoryTransactionLock(@Param("lockId") long lockId);

    @Query(nativeQuery = true, value = """
            select o.* from outbox o
            where o.published_at is null
              and not exists (select 1 from outbox_quarantine q where q.partition_key = o.partition_key)
            order by o.created_at, o.id
            limit :limit
            """)
    List<OutboxRow> findPublishable(@Param("limit") int limit);

    @Modifying
    @Query("update OutboxRow o set o.publishedAt = CURRENT_TIMESTAMP where o.id = :id and o.publishedAt is null")
    int markPublished(@Param("id") UUID id);

    @Modifying
    @Query(nativeQuery = true, value = """
            insert into outbox_quarantine (partition_key, outbox_id, reason)
            values (:partitionKey, :outboxId, :reason)
            on conflict (partition_key) do nothing
            """)
    int quarantine(
            @Param("partitionKey") String partitionKey,
            @Param("outboxId") UUID outboxId,
            @Param("reason") String reason);

    @Query(nativeQuery = true, value = """
            select coalesce(extract(epoch from now() - min(o.created_at)), 0) from outbox o
            where o.published_at is null
              and not exists (select 1 from outbox_quarantine q where q.partition_key = o.partition_key)
            """)
    double oldestPublishableAgeSeconds();

    @Query(nativeQuery = true, value = "select count(*) from outbox_quarantine")
    long countQuarantinedKeys();
}
