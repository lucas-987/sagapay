package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerIdempotencyRepository extends JpaRepository<LedgerIdempotency, LedgerIdempotencyId> {

    /** 1 row inserted = this call is first, its computed result is the one to use.
     * 0 rows = a concurrent replay already won; discard this result and read the
     * existing one back via {@code findById} instead. */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into ledger_idempotency (transfer_id, operation, result_json, created_at)
            values (:transferId, :operation, :resultJson, now())
            on conflict (transfer_id, operation) do nothing
            """)
    int insertIfAbsent(@Param("transferId") String transferId,
                        @Param("operation") String operation,
                        @Param("resultJson") String resultJson);
}
