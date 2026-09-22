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

    /** {@code FOR UPDATE SKIP LOCKED}: lets several poller runs (or instances)
     * share the work without ever publishing the same row twice or blocking each
     * other — each skips rows already claimed by another run instead of waiting.
     * Native query, not a JPQL {@code @Lock}: SKIP LOCKED has no JPQL equivalent. */
    @Query(nativeQuery = true, value = """
            select * from outbox
            where published_at is null
            order by created_at
            for update skip locked
            limit :limit
            """)
    List<OutboxRow> findUnpublishedForUpdateSkipLocked(@Param("limit") int limit);

    @Modifying
    @Query("update OutboxRow o set o.publishedAt = CURRENT_TIMESTAMP where o.id = :id and o.publishedAt is null")
    int markPublished(@Param("id") UUID id);
}
