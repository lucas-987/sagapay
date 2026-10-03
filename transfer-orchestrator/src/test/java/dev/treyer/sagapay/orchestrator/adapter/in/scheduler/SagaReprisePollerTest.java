package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the poller alone can complete a transfer stuck in {@code INITIATED}
 * or {@code RESERVED} -- not just that it doesn't break when the eager path
 * already finished. Transfers below are inserted directly in those states,
 * never through {@code SagaService.initiateTransfer()}/{@code advance()}. */
@Import({TestcontainersConfiguration.class, SagaReprisePollerTest.FakeLedgerPortConfig.class})
// Huge interval: only the explicit sweep() calls below may run the poller,
// never the scheduler in the middle of a test.
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

    /** With a zero grace period every sweep sees every leftover row, so each
     * test starts from an empty table and a happy-path fake. */
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
        UUID reservationId = status == TransferStatus.RESERVED ? UUID.randomUUID() : null;
        jdbcTemplate.update("""
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::transfer_status, ?)
                """,
                id, UUID.randomUUID(), UUID.randomUUID(), senderAccountId, UUID.randomUUID(), recipientAccountId,
                new BigDecimal("25.0000"), "EUR", status.name(), reservationId);
        return id;
    }

    private Transfer reload(UUID transferId) {
        return transferRepository.findById(transferId).orElseThrow();
    }

    @Test
    void pollerAloneCompletesATransferStuckInReservedPastTheGracePeriod() {
        UUID transferId = forceTransferInto(TransferStatus.RESERVED);

        reprisePoller.sweep();

        assertThat(reload(transferId).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isZero();
    }

    /** The eager call runs on a background task after the 202, so a crash can
     * leave the transfer INITIATED before the ledger is ever called. */
    @Test
    void pollerAloneCompletesATransferStuckInInitiatedPastTheGracePeriod() {
        UUID transferId = forceTransferInto(TransferStatus.INITIATED);

        reprisePoller.sweep();

        assertThat(reload(transferId).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isEqualTo(1);
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
    }

    /** E.g. the ledger's reservation expired during a long outage: retrying can
     * never succeed, so the transfer must fail rather than be retried forever. */
    @Test
    void pollerFailsAReservedTransferTheLedgerRefusesToPost() {
        UUID transferId = forceTransferInto(TransferStatus.RESERVED);
        fakeLedgerPort.willFailPost(new LedgerRejectedException("NOT_FOUND", "no matching reservation", null));

        reprisePoller.sweep();

        assertThat(reload(transferId).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(reload(transferId).getFailureReason()).isEqualTo("POST_REJECTED");

        reprisePoller.sweep();
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1); // not retried
    }

    @Test
    void pollerStopsAtTheFirstUnavailableLedgerCallAndLeavesTransfersForTheNextSweep() {
        UUID first = forceTransferInto(TransferStatus.INITIATED);
        UUID second = forceTransferInto(TransferStatus.INITIATED);
        UUID reserved = forceTransferInto(TransferStatus.RESERVED);
        fakeLedgerPort.willFailReserve(new LedgerUnavailableException(new RuntimeException("deadline exceeded")));

        reprisePoller.sweep();

        assertThat(fakeLedgerPort.checkAndReserveCallCount()).isEqualTo(1);
        assertThat(fakeLedgerPort.postTransferCallCount()).isZero();
        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(reload(reserved).getStatus()).isEqualTo(TransferStatus.RESERVED);

        fakeLedgerPort.reset(); // ledger back

        reprisePoller.sweep();

        assertThat(reload(first).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(reload(second).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(reload(reserved).getStatus()).isEqualTo(TransferStatus.POSTED);
    }

    @TestConfiguration
    static class FakeLedgerPortConfig {
        // @Primary: the real LedgerGrpcClientAdapter (§5) is also on the
        // classpath and satisfies LedgerPort too.
        @Bean
        @Primary
        FakeLedgerPort fakeLedgerPort() {
            return new FakeLedgerPort();
        }
    }

    /** Package-visible copy of {@code application.service.FakeLedgerPort}'s
     * shape: that one is package-private to its own test package, and this test
     * lives in a different package. */
    static class FakeLedgerPort implements LedgerPort {
        private RuntimeException reserveFailure;
        private RuntimeException postFailure;
        private int checkAndReserveCallCount = 0;
        private int postTransferCallCount = 0;

        void willFailReserve(RuntimeException failure) {
            this.reserveFailure = failure;
        }

        void willFailPost(RuntimeException failure) {
            this.postFailure = failure;
        }

        void reset() {
            reserveFailure = null;
            postFailure = null;
            checkAndReserveCallCount = 0;
            postTransferCallCount = 0;
        }

        int checkAndReserveCallCount() {
            return checkAndReserveCallCount;
        }

        int postTransferCallCount() {
            return postTransferCallCount;
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
            postTransferCallCount++;
            if (postFailure != null) {
                throw postFailure;
            }
            return true;
        }
    }
}
