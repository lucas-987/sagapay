package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.domain.Posting;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    /** {@code cast(:param as timestamp)} is required, not cosmetic: without it,
     * Postgres rejects the query when {@code from}/the cursor is null on the first
     * page ("could not determine data type of parameter") because the sole use of
     * that parameter is inside {@code ? is null}, giving it no type information
     * otherwise. */
    @Query("select p from Posting p where p.accountId = :accountId "
            + "and (cast(:from as timestamp) is null or p.createdAt >= :from) "
            + "and (cast(:afterCreatedAt as timestamp) is null "
            + "     or p.createdAt > :afterCreatedAt "
            + "     or (p.createdAt = :afterCreatedAt and p.id > :afterId)) "
            + "order by p.createdAt asc, p.id asc")
    List<Posting> findPage(@Param("accountId") UUID accountId,
                            @Param("from") Instant from,
                            @Param("afterCreatedAt") Instant afterCreatedAt,
                            @Param("afterId") UUID afterId,
                            Pageable pageable);
}
