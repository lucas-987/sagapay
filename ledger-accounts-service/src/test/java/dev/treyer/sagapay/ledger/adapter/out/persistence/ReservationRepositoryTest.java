package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ReservationRepositoryTest {

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    private UUID newAccount() {
        return accounts.saveAndFlush(
                        new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("1000.0000")))
                .getId();
    }

    // Flushed so raw SQL updates in the tests can see the row.
    private Reservation newReservation(UUID accountId, String transferId, String amount) {
        return reservations.saveAndFlush(new Reservation(
                UUID.randomUUID(),
                accountId,
                transferId,
                new BigDecimal(amount),
                Instant.now().plus(5, ChronoUnit.MINUTES)));
    }

    @Test
    void findByTransferIdFindsExistingReservation() {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();
        Reservation created = newReservation(accountId, transferId, "10.0000");

        Optional<Reservation> found = reservations.findByTransferId(transferId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(created.getId());
    }

    @Test
    void findByTransferIdIsEmptyForUnknownTransfer() {
        assertThat(reservations.findByTransferId(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void sumAmountByAccountIdAndStatusOnlySumsActiveReservations() {
        UUID accountId = newAccount();
        newReservation(accountId, UUID.randomUUID().toString(), "10.0000");
        newReservation(accountId, UUID.randomUUID().toString(), "5.0000");
        Reservation released = newReservation(accountId, UUID.randomUUID().toString(), "100.0000");
        reservations.updateStatusByReservationId(
                released.getTransferId(), released.getId(), ReservationStatus.ACTIVE, ReservationStatus.RELEASED);

        BigDecimal held = reservations.sumAmountByAccountIdAndStatus(accountId, ReservationStatus.ACTIVE);

        assertThat(held).isEqualByComparingTo("15.0000");
    }

    @Test
    void sumAmountByAccountIdAndStatusReturnsZeroWhenNoActiveReservations() {
        UUID accountId = newAccount();

        BigDecimal held = reservations.sumAmountByAccountIdAndStatus(accountId, ReservationStatus.ACTIVE);

        assertThat(held).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void updateStatusByReservationIdTransitionsOnceThenIsANoop() {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();
        Reservation reservation = newReservation(accountId, transferId, "10.0000");

        int firstUpdate = reservations.updateStatusByReservationId(
                transferId, reservation.getId(), ReservationStatus.ACTIVE, ReservationStatus.RELEASED);
        int secondUpdate = reservations.updateStatusByReservationId(
                transferId, reservation.getId(), ReservationStatus.ACTIVE, ReservationStatus.RELEASED);

        assertThat(firstUpdate).isEqualTo(1);
        assertThat(secondUpdate).isEqualTo(0);
    }

    @Test
    void updateStatusByReservationIdFailsSilentlyWhenReservationIdDoesNotMatch() {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();
        newReservation(accountId, transferId, "10.0000");
        UUID wrongReservationId = UUID.randomUUID();

        int updated = reservations.updateStatusByReservationId(
                transferId, wrongReservationId, ReservationStatus.ACTIVE, ReservationStatus.RELEASED);

        assertThat(updated).isEqualTo(0);
    }

    @Test
    void updateStatusByReservationIdTransitionsWhenReservationIdMatches() {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();
        Reservation reservation = newReservation(accountId, transferId, "10.0000");

        int updated = reservations.updateStatusByReservationId(
                transferId, reservation.getId(), ReservationStatus.ACTIVE, ReservationStatus.RELEASED);

        assertThat(updated).isEqualTo(1);
    }

    /** Expiry is forced in the database: nothing in the application ages a
     * reservation. */
    @Test
    void expireOverdueTransitionsOnlyExpiredActiveReservations() {
        UUID accountId = newAccount();
        Reservation expired = newReservation(accountId, UUID.randomUUID().toString(), "10.0000");
        Reservation fresh = newReservation(accountId, UUID.randomUUID().toString(), "20.0000");
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?", expired.getId());

        int updated = reservations.expireOverdue(ReservationStatus.ACTIVE, ReservationStatus.EXPIRED);

        assertThat(updated).isEqualTo(1);
        // The @Modifying update bypasses the persistence context.
        entityManager.clear();
        assertThat(reservations.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.findById(fresh.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
    }

    @Test
    void expireOverdueDoesNotTouchAlreadyReleasedReservations() {
        UUID accountId = newAccount();
        Reservation reservation = newReservation(accountId, UUID.randomUUID().toString(), "10.0000");
        reservations.updateStatusByReservationId(
                reservation.getTransferId(), reservation.getId(), ReservationStatus.ACTIVE, ReservationStatus.RELEASED);
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?", reservation.getId());

        int updated = reservations.expireOverdue(ReservationStatus.ACTIVE, ReservationStatus.EXPIRED);

        assertThat(updated).isEqualTo(0);
        entityManager.clear();
        assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }
}
