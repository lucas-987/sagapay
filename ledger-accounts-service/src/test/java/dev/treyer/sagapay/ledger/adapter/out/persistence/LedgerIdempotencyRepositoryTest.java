package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotency;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import dev.treyer.sagapay.ledger.domain.LedgerOperation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class LedgerIdempotencyRepositoryTest {

    @Autowired
    private LedgerIdempotencyRepository idempotency;

    @Test
    void insertIfAbsentReturnsOneOnFirstInsertThenZeroOnConflict() {
        String transferId = UUID.randomUUID().toString();

        int first = idempotency.insertIfAbsent(transferId, LedgerOperation.RESERVE.name(), "{\"status\":\"OK\"}");
        int second =
                idempotency.insertIfAbsent(transferId, LedgerOperation.RESERVE.name(), "{\"status\":\"DIFFERENT\"}");

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);

        Optional<LedgerIdempotency> row =
                idempotency.findById(new LedgerIdempotencyId(transferId, LedgerOperation.RESERVE));
        assertThat(row).isPresent();
        assertThat(row.get().getResultJson()).isEqualTo("{\"status\":\"OK\"}");
    }

    @Test
    void sameTransferIdDifferentOperationAreDistinctRows() {
        String transferId = UUID.randomUUID().toString();

        int reserveInsert = idempotency.insertIfAbsent(transferId, LedgerOperation.RESERVE.name(), "{}");
        int releaseInsert = idempotency.insertIfAbsent(transferId, LedgerOperation.RELEASE.name(), "{}");

        assertThat(reserveInsert).isEqualTo(1);
        assertThat(releaseInsert).isEqualTo(1); // the key includes the operation
    }

    @Test
    void findByIdIsEmptyForUnknownKey() {
        Optional<LedgerIdempotency> row =
                idempotency.findById(new LedgerIdempotencyId(UUID.randomUUID().toString(), LedgerOperation.POST));

        assertThat(row).isEmpty();
    }
}
