package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException;
import org.awaitility.Awaitility;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The background sweeps are pushed out of the way so that only the two racing threads act. */
@Import({TestcontainersConfiguration.class, ConfirmExpiryRaceTest.RecordingLedgerConfig.class})
@SpringBootTest(properties = {"saga.deadline.sweep-interval-ms=3600000", "saga.reprise.sweep-interval-ms=3600000"})
class ConfirmExpiryRaceTest {

    private static final String CODE = "test-verification-code";
    private static final int ITERATIONS = 100;
    private static final int LEAD_STEPS = 20;
    private static final long LEAD_STEP_MICROS = 250;
    private static final int LOCKED_ITERATIONS = 5;
    /** Long enough for a loaded machine to queue the confirmation before the deadline. */
    private static final Duration LOCKED_LEAD = Duration.ofSeconds(3);

    @Autowired
    private SagaService sagaService;

    @Autowired
    private SagaTransitionWriter writer;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private RecordingLedgerPort ledger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private record Outcome(boolean confirmAccepted, boolean confirmRefusedAsExpired, int expired) {}

    /** The lead moves the deadline across the instant both threads reach their compare-and-set,
     * so the race is run on each side of it: from 0 to (LEAD_STEPS - 1) * LEAD_STEP_MICROS
     * microseconds, about the time a thread takes to start and reach the database. */
    private Outcome raceAtTheDeadline(Transfer transfer, long leadMicros) throws Exception {
        Instant deadline = Instant.now().plusNanos(leadMicros * 1000);
        setOpenWindowDeadline(transfer.getId(), deadline);
        return race(transfer, deadline, () -> {});
    }

    /** Releases both racers together, runs {@code whileRacing}, then collects their results. */
    private Outcome race(Transfer transfer, Instant deadline, Runnable whileRacing) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<int[]> confirm = pool.submit(confirmingTask(transfer.getId(), start));
            Future<Integer> expiry = pool.submit(expiryTask(deadline, start));
            start.countDown();
            whileRacing.run();
            return collect(confirm, expiry);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome collect(Future<int[]> confirm, Future<Integer> expiry) throws Exception {
        int[] confirmation = confirm.get();
        return new Outcome(confirmation[0] == 1, confirmation[1] == 1, expiry.get());
    }

    private Callable<int[]> confirmingTask(UUID transferId, CountDownLatch start) {
        return () -> {
            start.await();
            try {
                sagaService.confirmTransfer(transferId, CODE);
            } catch (VerificationExpiredException e) {
                return new int[] {0, 1};
            }
            sagaService.postCleared(transferId);
            return new int[] {1, 0};
        };
    }

    /** The sweep starts once the deadline has passed, as the scheduled job does. */
    private Callable<Integer> expiryTask(Instant deadline, CountDownLatch start) {
        return () -> {
            start.await();
            while (Instant.now().isBefore(deadline)) {
                Thread.onSpinWait();
            }
            return sagaService.sweepOverdueTransfers();
        };
    }

    private Transfer blockedTransfer(UUID reservationId) {
        ledger.willReserve(reservationId);
        Transfer transfer = sagaService
                .initiateTransfer(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.of("80.00", "EUR"),
                        UUID.randomUUID(),
                        null)
                .transfer();
        sagaService.advance(transfer.getId());
        assertThat(writer.applyFlagged(new FraudResult(
                        UUID.randomUUID(),
                        transfer.getId(),
                        FraudDecision.FLAGGED,
                        new BigDecimal("0.9000"),
                        List.of("VELOCITY_1H"))))
                .isTrue();
        return transfer;
    }

    private void setOpenWindowDeadline(UUID transferId, Instant deadline) {
        jdbcTemplate.update("""
                update saga_steps set deadline = ?
                where id = (select max(id) from saga_steps where transfer_id = ? and step = 'SCREEN')
                """, Timestamp.from(deadline), transferId);
    }

    @Test
    @Tag("M3-S29")
    @DisplayName("Confirmation and expiry racing at the deadline never both win")
    void confirmationAndExpiryRacingAtTheDeadlineNeverBothWin() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            UUID reservationId = UUID.randomUUID();
            Transfer transfer = blockedTransfer(reservationId);

            Outcome outcome = raceAtTheDeadline(transfer, (i % LEAD_STEPS) * LEAD_STEP_MICROS);

            verifyOneWinner(failures, i, transfer, reservationId, outcome);
        }

        assertThat(failures).isEmpty();
    }

    /** Both updates are queued on the row before the lock is released, so the database, not the
     * timing of the threads, picks the first and the second must find the status changed. */
    @Test
    @DisplayName("Two updates waiting on the same blocked row never both succeed")
    void twoUpdatesWaitingOnTheSameBlockedRowNeverBothSucceed() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < LOCKED_ITERATIONS; i++) {
            UUID reservationId = UUID.randomUUID();
            Transfer transfer = blockedTransfer(reservationId);

            Outcome outcome = raceBehindARowLock(transfer);

            verifyOneWinner(failures, i, transfer, reservationId, outcome);
        }

        assertThat(failures).isEmpty();
    }

    /** The confirmation's deadline checks pass, so only the compare-and-set on the status can
     * refuse it once the sweep has ended the transfer. */
    @Test
    @DisplayName("A confirmation queued behind the sweep on the same blocked row is refused")
    void aConfirmationQueuedBehindTheSweepOnTheSameBlockedRowIsRefused() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < LOCKED_ITERATIONS; i++) {
            UUID reservationId = UUID.randomUUID();
            Transfer transfer = blockedTransfer(reservationId);

            Outcome outcome = sweepQueuedBeforeConfirmationBehindARowLock(transfer);

            verifyOneWinner(failures, i, transfer, reservationId, outcome);
            check(failures, i, "the sweep won", outcome.expired(), 1);
        }

        assertThat(failures).isEmpty();
    }

    /** The confirmation is checked and queued on the locked row before the deadline, the sweep
     * after it: the confirmation is the first updater. */
    private Outcome raceBehindARowLock(Transfer transfer) throws Exception {
        Instant deadline = Instant.now().plus(LOCKED_LEAD);
        setOpenWindowDeadline(transfer.getId(), deadline);
        try (Connection lock = lockTransferRow(transfer.getId())) {
            return race(transfer, deadline, () -> {
                awaitUpdatesWaitingOnTheLock(1);
                assertThat(Instant.now())
                        .as("the confirmation queued before the deadline")
                        .isBefore(deadline);
                awaitUpdatesWaitingOnTheLock(2);
                commit(lock);
            });
        }
    }

    /** The deadline has passed, so the sweep queues its update first. The deadline is then moved
     * ahead, so that the confirmation passes its deadline checks and only the status of the row
     * can refuse it. */
    private Outcome sweepQueuedBeforeConfirmationBehindARowLock(Transfer transfer) throws Exception {
        setOpenWindowDeadline(transfer.getId(), Instant.now().minusSeconds(1));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection lock = lockTransferRow(transfer.getId())) {
            start.countDown();
            Future<Integer> expiry = pool.submit(expiryTask(Instant.now(), start));
            awaitUpdatesWaitingOnTheLock(1);
            setOpenWindowDeadline(transfer.getId(), Instant.now().plus(LOCKED_LEAD));
            Future<int[]> confirm = pool.submit(confirmingTask(transfer.getId(), start));
            awaitUpdatesWaitingOnTheLock(2);
            commit(lock);
            return collect(confirm, expiry);
        } finally {
            pool.shutdownNow();
        }
    }

    private Connection lockTransferRow(UUID transferId) throws Exception {
        Connection lock = dataSource.getConnection();
        lock.setAutoCommit(false);
        try (PreparedStatement statement = lock.prepareStatement("select id from transfers where id = ? for update")) {
            statement.setObject(1, transferId);
            statement.execute();
        }
        return lock;
    }

    private static void commit(Connection lock) {
        try {
            lock.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** At least {@code count}: the wait does not depend on no other session waiting. */
    private void awaitUpdatesWaitingOnTheLock(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            return waiting != null && waiting >= count;
        });
    }

    private void verifyOneWinner(
            List<String> failures, int iteration, Transfer transfer, UUID reservationId, Outcome outcome) {
        Transfer after = transferRepository.findById(transfer.getId()).orElseThrow();
        String id = transfer.getId().toString();
        List<String> calls = ledger.callsFor(id);
        if (outcome.confirmAccepted()) {
            check(
                    failures,
                    iteration,
                    "an accepted confirmation ends POSTED",
                    after.getStatus(),
                    TransferStatus.POSTED);
            check(failures, iteration, "the expiry sweep ends nothing", outcome.expired(), 0);
            check(failures, iteration, "the ledger receives one post", calls, List.of("POST " + id));
        } else {
            check(
                    failures,
                    iteration,
                    "the confirmation is refused as expired",
                    outcome.confirmRefusedAsExpired(),
                    true);
            check(failures, iteration, "expiry ends FAILED", after.getStatus(), TransferStatus.FAILED);
            check(failures, iteration, "expiry reason", after.getFailureReason(), "VERIFICATION_EXPIRED");
            check(failures, iteration, "the expiry sweep ends one transfer", outcome.expired(), 1);
            check(
                    failures,
                    iteration,
                    "the ledger receives one release",
                    calls,
                    List.of("RELEASE " + id + " " + reservationId));
        }
    }

    private static void check(List<String> failures, int iteration, String what, Object actual, Object expected) {
        if (!expected.equals(actual)) {
            failures.add("iteration " + iteration + ": " + what + ": expected " + expected + " but was " + actual);
        }
    }

    /** Thread-safe, unlike the shared fake: both racing threads call it. */
    static class RecordingLedgerPort implements LedgerPort {

        private final Queue<String> calls = new ConcurrentLinkedQueue<>();
        private volatile UUID nextReservationId;

        void willReserve(UUID reservationId) {
            this.nextReservationId = reservationId;
        }

        List<String> callsFor(String transferId) {
            return calls.stream().filter(call -> call.contains(transferId)).toList();
        }

        @Override
        public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
            return new ReservationResult.Ok(nextReservationId);
        }

        @Override
        public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
            calls.add("POST " + transferId);
            return true;
        }

        @Override
        public void releaseReservation(String transferId, UUID reservationId) {
            calls.add("RELEASE " + transferId + " " + reservationId);
        }
    }

    @TestConfiguration
    static class RecordingLedgerConfig {
        @Bean
        @Primary
        RecordingLedgerPort recordingLedgerPort() {
            return new RecordingLedgerPort();
        }
    }
}
