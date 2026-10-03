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

    /** SKIP LOCKED lets concurrent pollers share rows without waiting or
     * publishing twice; it has no JPQL equivalent. */
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
