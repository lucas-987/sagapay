package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
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

/** Proves the poller alone can complete a transfer stuck in {@code RESERVED} --
 * not just that it doesn't break when the eager path already finished. The
 * transfer below is inserted directly at {@code RESERVED}, never through
 * {@code SagaService.advance()}. */
@Import({TestcontainersConfiguration.class, SagaReprisePollerTest.FakeLedgerPortConfig.class})
@TestPropertySource(properties = "saga.reprise.grace-period-ms=0")
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

    private UUID forceTransferIntoReservedWithoutRunningTheEagerChain() {
        UUID id = UUID.randomUUID();
        UUID senderAccountId = UUID.randomUUID();
        UUID recipientAccountId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency, status, reservation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED'::transfer_status, ?)
                """,
                id, UUID.randomUUID(), UUID.randomUUID(), senderAccountId, UUID.randomUUID(), recipientAccountId,
                new BigDecimal("25.0000"), "EUR", UUID.randomUUID());
        return id;
    }

    @Test
    void pollerAloneCompletesATransferStuckInReservedPastTheGracePeriod() {
        UUID transferId = forceTransferIntoReservedWithoutRunningTheEagerChain();
        fakeLedgerPort.willPost(true);

        reprisePoller.sweep();

        assertThat(transferRepository.findById(transferId).orElseThrow().getStatus())
                .isEqualTo(TransferStatus.POSTED);
        assertThat(fakeLedgerPort.postTransferCallCount()).isEqualTo(1);
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
     * lives in a different package. Kept minimal -- only {@code postTransfer} is
     * exercised here, {@code checkAndReserve} is never called by the poller. */
    static class FakeLedgerPort implements LedgerPort {
        private boolean postResult = true;
        private int postTransferCallCount = 0;

        void willPost(boolean result) {
            this.postResult = result;
        }

        int postTransferCallCount() {
            return postTransferCallCount;
        }

        @Override
        public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
            throw new UnsupportedOperationException("not exercised by SagaReprisePollerTest");
        }

        @Override
        public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
            postTransferCallCount++;
            return postResult;
        }
    }
}
