package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Transfers are inserted directly in their stuck state, so only the poller can
 * move them on. */
@Import({TestcontainersConfiguration.class, SagaReprisePollerTest.FakeLedgerPortConfig.class})
// Only the explicit sweep() calls may run the poller.
@TestPropertySource(properties = {"saga.reprise.grace-period-ms=0", "saga.reprise.sweep-interval-ms=3600000"})
@SpringBootTest
class SagaReprisePollerTest {

    @Autowired
    private SagaReprisePoller reprisePoller;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLedgerPort fakeLedgerPort;

    /** With no grace period, every sweep sees every leftover row. */
    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from saga_steps");
        jdbcTemplate.update("delete from transfers");
        fakeLedgerPort.reset();
    }

    private UUID forceTransferInto(TransferStatus status) {
        UUID id = UUID.randomUUID();
        UUID senderAccountId = UUID.randomUUID();
        UUID recipientAccountId = UUID.randomUUID();
        UUID reservationId = status == TransferStatus.INITIATED ? null : UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::transfer_status, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                senderAccountId,
                UUID.randomUUID(),
                recipientAccountId,
                new BigDecimal("25.0000"),
                "EUR",
                status.name(),
                reservationId);
        return id;
    }

    private UUID forceFailedTransferWithRelease(String... releaseOutcomes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, failure_reason, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'FAILED'::transfer_status, 'POST_REJECTED', ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.0000"),
                "EUR",
                UUID.randomUUID());
        for (String outcome : releaseOutcomes) {
            jdbcTemplate.update(
                    "insert into saga_steps (transfer_id, step, outcome) values (?, 'RELEASE', ?)", id, outcome);
        }
        return id;
    }

    private List<String> releaseOutcomes(UUID transferId) {
        return jdbcTemplate.queryForList(
                "select outcome from saga_steps where transfer_id = ? and step = 'RELEASE' order by id",
                String.class,
                transferId);
    }

    private Transfer reload(UUID transferId) {
        return transferRepository.findById(transferId).orElseThrow();
    }

    @Test
    @Tag("M3-S05")
    @DisplayName("A cleared transfer whose posting was interrupted is posted by recovery")
    void pollerPostsAClearedTransferOnceAndNeverAReservedOrScreeningOne() {
        UUID cleared = forceTransferInto(TransferStatus.CLEARED);
        UUID reserved = forceTransferInto(TransferStatus.RESERVED);
        UUID screening = forceTransferInto(TransferStatus.SCREENING);

        reprisePoller.sweep();
        reprisePoller.sweep();

        assertThat(reload(cleared).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(reload(reserved).getStatus()).isEqualTo(TransferStatus.RESERVED);
        assertThat(reload(screening).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(fakeLedgerPort.postedTransferIds()).containsExactly(cleared.toString());
        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isZero();
    }

    /** The eager call runs on a background task after the 202, so a crash can
     * leave the transfer INITIATED before the ledger is ever called. */
    @Test
    void pollerAloneReservesATransferStuckInInitiatedPastTheGracePeriodAndHoldsItForScreening() {
        UUID transferId = forceTransferInto(TransferStatus.INITIATED);

        reprisePoller.sweep();

        assertThat(reload(transferId).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isEqualTo(1);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
    }

    /** For instance an expired reservation: retrying can never succeed. */
    @Test
    void pollerFailsAClearedTransferTheLedgerRefusesToPost() {
        UUID transferId = forceTransferInto(TransferStatus.CLEARED);
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));

        reprisePoller.sweep();

        assertThat(reload(transferId).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(reload(transferId).getFailureReason()).isEqualTo(FailureReason.POST_REJECTED);

        reprisePoller.sweep();
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
    }

    @Test
    void pollerStopsAtTheFirstUnavailableLedgerCallAndLeavesTransfersForTheNextSweep() {
        UUID first = forceTransferInto(TransferStatus.INITIATED);
        UUID second = forceTransferInto(TransferStatus.INITIATED);
        UUID cleared = forceTransferInto(TransferStatus.CLEARED);
        fakeLedgerPort.willFailReserve(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        reprisePoller.sweep();

        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isEqualTo(1);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(reload(cleared).getStatus()).isEqualTo(TransferStatus.CLEARED);

        fakeLedgerPort.reset(); // ledger back

        reprisePoller.sweep();

        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(reload(cleared).getStatus()).isEqualTo(TransferStatus.POSTED);
    }

    @Test
    void pollerReleasesTheReservationOfAFailedTransferWhoseReleaseWasLeftPending() {
        UUID transferId = forceFailedTransferWithRelease("RETRY");

        reprisePoller.sweep();

        assertThat(fakeLedgerPort.releaseCallCount()).isEqualTo(1);
        assertThat(releaseOutcomes(transferId)).containsExactly("RETRY", "COMPENSATED");

        reprisePoller.sweep();
        assertThat(fakeLedgerPort.releaseCallCount()).isEqualTo(1);
    }

    @Test
    void pollerKeepsRetryingTheReleaseWhileTheLedgerIsUnavailable() {
        UUID transferId = forceFailedTransferWithRelease("RETRY");
        fakeLedgerPort.willFailRelease(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        reprisePoller.sweep();

        assertThat(releaseOutcomes(transferId)).containsExactly("RETRY");

        fakeLedgerPort.reset(); // ledger back
        reprisePoller.sweep();

        assertThat(releaseOutcomes(transferId)).containsExactly("RETRY", "COMPENSATED");
    }

    @Test
    void pollerDoesNotRetryAReleaseTheLedgerRefused() {
        UUID transferId = forceFailedTransferWithRelease("RETRY");
        fakeLedgerPort.willFailRelease(new LedgerRejectedException("INVALID_ARGUMENT", "bad id", null));

        reprisePoller.sweep();
        reprisePoller.sweep();

        assertThat(fakeLedgerPort.releaseCallCount()).isEqualTo(1);
        assertThat(releaseOutcomes(transferId)).containsExactly("RETRY", "FAILED");
    }

    @Test
    void pollerIgnoresFailedTransfersWithNothingToRelease() {
        forceFailedTransferWithRelease("RETRY", "COMPENSATED");
        forceFailedTransferWithRelease();

        reprisePoller.sweep();

        assertThat(fakeLedgerPort.releaseCallCount()).isZero();
    }

    @Test
    void pollerStopsReleasingAtTheFirstUnavailableLedgerCall() {
        forceFailedTransferWithRelease("RETRY");
        forceFailedTransferWithRelease("RETRY");
        fakeLedgerPort.willFailRelease(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        reprisePoller.sweep();

        assertThat(fakeLedgerPort.releaseCallCount()).isEqualTo(1);
    }

    @TestConfiguration
    static class FakeLedgerPortConfig {
        @Bean
        @Primary
        FakeLedgerPort fakeLedgerPort() {
            return new FakeLedgerPort();
        }
    }

    static class FakeLedgerPort implements LedgerPort {
        private RuntimeException reserveFailure;
        private RuntimeException postFailure;
        private int checkAndReserveCallCount = 0;
        private final List<String> postedTransferIds = new ArrayList<>();
        private RuntimeException releaseFailure;
        private int releaseCallCount = 0;

        void willFailRelease(RuntimeException failure) {
            this.releaseFailure = failure;
        }

        int releaseCallCount() {
            return releaseCallCount;
        }

        void willFailReserve(RuntimeException failure) {
            this.reserveFailure = failure;
        }

        void willFailPost(RuntimeException failure) {
            this.postFailure = failure;
        }

        void reset() {
            reserveFailure = null;
            postFailure = null;
            releaseFailure = null;
            releaseCallCount = 0;
            checkAndReserveCallCount = 0;
            postedTransferIds.clear();
        }

        int checkAndReserveCallCount() {
            return checkAndReserveCallCount;
        }

        int postTransferCallCount() {
            return postedTransferIds.size();
        }

        List<String> postedTransferIds() {
            return postedTransferIds;
        }

        @Override
        public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
            checkAndReserveCallCount++;
            if (reserveFailure != null) {
                throw reserveFailure;
            }
            return new ReservationResult.Ok(UUID.randomUUID());
        }

        @Override
        public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
            postedTransferIds.add(transferId);
            if (postFailure != null) {
                throw postFailure;
            }
            return true;
        }

        @Override
        public void releaseReservation(String transferId, UUID reservationId) {
            releaseCallCount++;
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }
}
