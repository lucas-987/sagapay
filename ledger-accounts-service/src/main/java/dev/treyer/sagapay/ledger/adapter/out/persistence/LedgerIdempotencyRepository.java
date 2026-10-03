package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerIdempotencyRepository extends JpaRepository<LedgerIdempotency, LedgerIdempotencyId> {

    /** @return 1 when this call is first; 0 when a concurrent replay already stored
     * its result, which must then be read back. */
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
