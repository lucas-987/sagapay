package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
                "pizza",
                Instant.now());

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
                null,
                Instant.now());
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
                null,
                Instant.now()));
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    void everyStatusRoundTripsThroughTheNativeEnumColumn(TransferStatus status) {
        Transfer saved = newTransfer();
        jdbcTemplate.update("update transfers set status = ? where id = ?", status.name(), saved.getId());
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

    private UUID transferWith(TransferStatus status, Instant updatedAt, String... releaseOutcomes) {
        UUID id = newTransfer().getId();
        jdbcTemplate.update(
                "update transfers set status = ?, updated_at = ? where id = ?",
                status.name(),
                java.sql.Timestamp.from(updatedAt),
                id);
        for (String outcome : releaseOutcomes) {
            jdbcTemplate.update(
                    "insert into saga_steps (transfer_id, step, outcome) values (?, 'RELEASE', ?)", id, outcome);
        }
        entityManager.clear();
        return id;
    }

    @Test
    void findFailedWithPendingReleaseReturnsTheOldestFlaggedTransfersUpToTheLimit() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(1, ChronoUnit.MINUTES);
        UUID older = pendingRelease(now.minus(2, ChronoUnit.HOURS));
        UUID newer = pendingRelease(now.minus(1, ChronoUnit.HOURS));
        pendingRelease(now.minus(30, ChronoUnit.MINUTES));
        pendingRelease(now);
        transferWith(TransferStatus.FAILED, now.minus(3, ChronoUnit.HOURS));

        List<Transfer> found = transfers.findFailedWithPendingRelease(cutoff, PageRequest.ofSize(2));

        assertThat(found).extracting(Transfer::getId).containsExactly(older, newer);
    }

    private UUID pendingRelease(Instant updatedAt) {
        UUID id = transferWith(TransferStatus.FAILED, updatedAt);
        jdbcTemplate.update("update transfers set release_pending = true where id = ?", id);
        return id;
    }

    @Test
    void findByStatusAndUpdatedAtBeforeTakesTheLongestWaitingFirstUpToTheLimit() {
        Instant now = Instant.now();
        UUID older = transferWith(TransferStatus.CLEARED, now.minus(3, ChronoUnit.HOURS));
        UUID newer = transferWith(TransferStatus.CLEARED, now.minus(2, ChronoUnit.HOURS));
        transferWith(TransferStatus.CLEARED, now.minus(1, ChronoUnit.HOURS));
        transferWith(TransferStatus.POSTED, now.minus(4, ChronoUnit.HOURS));

        List<Transfer> found = transfers.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAscIdAsc(
                TransferStatus.CLEARED, now.minus(1, ChronoUnit.MINUTES), PageRequest.ofSize(2));

        assertThat(found).extracting(Transfer::getId).containsExactly(older, newer);
    }

    @Test
    void aResultAfterTheScreeningDeadlineMovesNothingBeforeTheSweepRuns() {
        Instant now = Instant.now();
        UUID late = transferWithScreenDeadlines(TransferStatus.SCREENING, now.minusSeconds(1));
        BigDecimal score = new BigDecimal("0.1000");

        assertThat(transfers.transitionToCleared(late, score, List.of(), now)).isZero();
        assertThat(transfers.transitionToBlocked(late, score, List.of(), now)).isZero();
        assertThat(transfers.transitionFlaggedToExpired(late, score, List.of(), now))
                .isZero();
    }

    @Test
    void aResultAtTheScreeningDeadlineIsStillInTime() {
        Instant now = Instant.now();
        UUID cleared = transferWithScreenDeadlines(TransferStatus.SCREENING, now);
        UUID blocked = transferWithScreenDeadlines(TransferStatus.SCREENING, now);
        BigDecimal score = new BigDecimal("0.1000");

        assertThat(transfers.transitionToCleared(cleared, score, List.of(), now))
                .isEqualTo(1);
        assertThat(transfers.transitionToBlocked(blocked, score, List.of(), now))
                .isEqualTo(1);
    }

    private UUID transferWithScreenDeadlines(TransferStatus status, Instant... screenRetryDeadlines) {
        UUID id = transferWith(status, Instant.now());
        for (Instant deadline : screenRetryDeadlines) {
            jdbcTemplate.update(
                    "insert into saga_steps (transfer_id, step, outcome, deadline) values (?, 'SCREEN', 'RETRY', ?)",
                    id,
                    java.sql.Timestamp.from(deadline));
        }
        return id;
    }

    @Test
    void findOverdueReturnsExactlyTheTransfersInThatStatusWhoseLatestScreenDeadlineIsPast() {
        Instant now = Instant.now();
        Instant past = now.minus(5, ChronoUnit.SECONDS);
        Instant future = now.plus(5, ChronoUnit.SECONDS);
        UUID screeningOverdue = transferWithScreenDeadlines(TransferStatus.SCREENING, past);
        transferWithScreenDeadlines(TransferStatus.SCREENING, future);
        transferWithScreenDeadlines(TransferStatus.SCREENING);
        transferWithScreenDeadlines(TransferStatus.BLOCKED, past, past);
        transferWithScreenDeadlines(TransferStatus.CLEARED, past);
        // The confirmation window of a blocked transfer replaces its screening deadline.
        transferWithScreenDeadlines(TransferStatus.BLOCKED, past, future);

        List<Transfer> found = transfers.findOverdue(TransferStatus.SCREENING.name(), now, 10, "SCREEN", "RETRY");

        assertThat(found).extracting(Transfer::getId).containsExactly(screeningOverdue);
    }

    @Test
    void findOverdueReturnsABlockedTransferWhoseConfirmationWindowIsPast() {
        Instant now = Instant.now();
        Instant past = now.minus(5, ChronoUnit.SECONDS);
        UUID blockedOverdue = transferWithScreenDeadlines(TransferStatus.BLOCKED, now.plus(1, ChronoUnit.HOURS), past);
        transferWithScreenDeadlines(TransferStatus.SCREENING, past);

        List<Transfer> found = transfers.findOverdue(TransferStatus.BLOCKED.name(), now, 10, "SCREEN", "RETRY");

        assertThat(found).extracting(Transfer::getId).containsExactly(blockedOverdue);
    }

    @ParameterizedTest
    @CsvSource({"-1, true", "0, false", "1, false"})
    void findOverdueIsStrictlyBeforeNow(long deadlineOffsetMillis, boolean returned) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = transferWithScreenDeadlines(TransferStatus.SCREENING, now.plusMillis(deadlineOffsetMillis));

        List<Transfer> found = transfers.findOverdue(TransferStatus.SCREENING.name(), now, 10, "SCREEN", "RETRY");

        assertThat(found).extracting(Transfer::getId).isEqualTo(returned ? List.of(id) : List.of());
    }

    @Test
    void findOverdueReturnsTheOldestDeadlinesFirstThenByIdAndStopsAtTheLimit() {
        Instant now = Instant.now();
        Instant older = now.minus(10, ChronoUnit.SECONDS);
        Instant newer = now.minus(5, ChronoUnit.SECONDS);
        UUID newest = transferWithScreenDeadlines(TransferStatus.SCREENING, newer);
        UUID oldest = transferWithScreenDeadlines(TransferStatus.SCREENING, older);
        UUID tieA = transferWithScreenDeadlines(TransferStatus.SCREENING, newer);
        List<UUID> tied = Stream.of(newest, tieA)
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();

        List<Transfer> found = transfers.findOverdue(TransferStatus.SCREENING.name(), now, 2, "SCREEN", "RETRY");

        assertThat(found).extracting(Transfer::getId).containsExactly(oldest, tied.get(0));
    }

    private TransferStatus statusOf(UUID id) {
        return TransferStatus.valueOf(
                jdbcTemplate.queryForObject("select status::text from transfers where id = ?", String.class, id));
    }

    @ParameterizedTest
    @CsvSource({"-1, 0, BLOCKED", "0, 1, CLEARED", "1, 1, CLEARED"})
    void transitionBlockedToClearedHoldsWhileTheDeadlineIsNotPast(
            long deadlineOffsetMillis, int updated, TransferStatus expected) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID id = transferWithScreenDeadlines(TransferStatus.BLOCKED, now.plusMillis(deadlineOffsetMillis));

        assertThat(transfers.transitionBlockedToCleared(id, now, "SCREEN", "RETRY"))
                .isEqualTo(updated);

        assertThat(statusOf(id)).isEqualTo(expected);
    }

    @Test
    void transitionBlockedToClearedFollowsTheLatestScreenDeadline() {
        Instant now = Instant.now();
        UUID id = transferWithScreenDeadlines(
                TransferStatus.BLOCKED, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

        assertThat(transfers.transitionBlockedToCleared(id, now, "SCREEN", "RETRY"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = TransferStatus.class,
            names = {"BLOCKED"},
            mode = EnumSource.Mode.EXCLUDE)
    void transitionBlockedToClearedLeavesAnyOtherStatusAlone(TransferStatus status) {
        Instant now = Instant.now();
        UUID id = transferWithScreenDeadlines(status, now.plus(1, ChronoUnit.HOURS));

        assertThat(transfers.transitionBlockedToCleared(id, now, "SCREEN", "RETRY"))
                .isZero();

        assertThat(statusOf(id)).isEqualTo(status);
    }

    @Test
    void transitionBlockedToClearedLeavesATransferWithoutAnOpenWindowAlone() {
        UUID id = transferWithScreenDeadlines(TransferStatus.BLOCKED);

        assertThat(transfers.transitionBlockedToCleared(id, Instant.now(), "SCREEN", "RETRY"))
                .isZero();
    }

    @Test
    void aTransferAlreadyLoadedIsReadAsClearedAfterTheTransition() {
        Instant now = Instant.now();
        UUID id = transferWithScreenDeadlines(TransferStatus.BLOCKED, now.plus(1, ChronoUnit.HOURS));
        assertThat(transfers.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.BLOCKED);

        transfers.transitionBlockedToCleared(id, now, "SCREEN", "RETRY");

        assertThat(transfers.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.CLEARED);
    }
}
