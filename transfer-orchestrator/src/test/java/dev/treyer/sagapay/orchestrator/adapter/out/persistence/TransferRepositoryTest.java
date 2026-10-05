package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class TransferRepositoryTest {

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    private void insertRaw(UUID id, UUID senderId, UUID idempotencyKey) {
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                idempotencyKey,
                senderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("10.0000"),
                "EUR");
    }

    // Raw SQL, to test the database constraint itself.
    @Test
    void uniqueSenderIdAndIdempotencyKeyIsEnforcedAtTheDatabaseLevel() {
        UUID senderId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        insertRaw(UUID.randomUUID(), senderId, idempotencyKey);

        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), senderId, idempotencyKey))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDifferentSenderCanReuseTheSameIdempotencyKey() {
        UUID idempotencyKey = UUID.randomUUID();
        insertRaw(UUID.randomUUID(), UUID.randomUUID(), idempotencyKey);

        // The constraint is on the pair.
        insertRaw(UUID.randomUUID(), UUID.randomUUID(), idempotencyKey);
    }

    @Test
    void saveAndFindByIdRoundTripsAllFieldsIncludingTheNativeEnumStatusColumn() {
        UUID senderId = UUID.randomUUID();
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                senderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("80.0000"),
                "EUR",
                "pizza");

        Transfer saved = transfers.saveAndFlush(transfer);
        entityManager.clear();
        Transfer found = transfers.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(found.getSenderId()).isEqualTo(senderId);
        assertThat(found.getAmount()).isEqualByComparingTo("80.0000");
        assertThat(found.getNote()).isEqualTo("pizza");
    }

    @Test
    void findBySenderIdAndIdempotencyKeyFindsTheExistingTransfer() {
        UUID senderId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        Transfer transfer = new Transfer(
                UUID.randomUUID(),
                idempotencyKey,
                senderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("5.0000"),
                "EUR",
                null);
        transfers.saveAndFlush(transfer);

        assertThat(transfers.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey))
                .isPresent();
        assertThat(transfers.findBySenderIdAndIdempotencyKey(senderId, UUID.randomUUID()))
                .isEmpty();
    }

    private Transfer newTransfer() {
        return transfers.saveAndFlush(new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("5.0000"),
                "EUR",
                null));
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    void everyStatusRoundTripsThroughTheNativeEnumColumn(TransferStatus status) {
        Transfer saved = newTransfer();
        jdbcTemplate.update(
                "update transfers set status = ?::transfer_status where id = ?", status.name(), saved.getId());
        entityManager.clear();

        assertThat(transfers.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void fraudScoreAndReasonsRoundTrip() {
        Transfer saved = newTransfer();
        jdbcTemplate.update(
                "update transfers set fraud_score = ?, fraud_reasons = ?::text[] where id = ?",
                new BigDecimal("0.8765"),
                "{HIGH_AMOUNT,NEW_RECIPIENT}",
                saved.getId());
        entityManager.clear();

        Transfer found = transfers.findById(saved.getId()).orElseThrow();

        assertThat(found.getFraudScore()).isEqualByComparingTo("0.8765");
        assertThat(found.getFraudReasons()).containsExactly("HIGH_AMOUNT", "NEW_RECIPIENT");
    }

    @Test
    void fraudScoreAndReasonsAreAbsentByDefault() {
        Transfer saved = newTransfer();
        entityManager.clear();

        Transfer found = transfers.findById(saved.getId()).orElseThrow();

        assertThat(found.getFraudScore()).isNull();
        assertThat(found.getFraudReasons()).isNull();
    }
}
