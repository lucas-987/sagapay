package dev.treyer.sagapay.ledger.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.adapter.out.persistence.PostingRepository;
import dev.treyer.sagapay.ledger.adapter.out.persistence.ReservationRepository;
import dev.treyer.sagapay.ledger.application.port.in.CheckAndReserveUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ExpireReservationsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.GetWalletUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ListPostingsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.PostTransferUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ReleaseReservationUseCase;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.domain.CheckAndReserveResult;
import dev.treyer.sagapay.ledger.domain.CurrencyMismatchException;
import dev.treyer.sagapay.ledger.domain.IdempotencyConflictException;
import dev.treyer.sagapay.ledger.domain.InvalidAmountException;
import dev.treyer.sagapay.ledger.domain.NoMatchingReservationException;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingLeg;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import dev.treyer.sagapay.ledger.domain.WalletSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real Postgres: row locks and CHECK constraints cannot be mocked. No
 * test transaction: concurrent threads must see committed state. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0")
class LedgerServiceTest {

    @Autowired
    private CheckAndReserveUseCase checkAndReserveUseCase;

    @Autowired
    private PostTransferUseCase postTransferUseCase;

    @Autowired
    private ReleaseReservationUseCase releaseReservationUseCase;

    @Autowired
    private GetWalletUseCase getWalletUseCase;

    @Autowired
    private ListPostingsUseCase listPostingsUseCase;

    @Autowired
    private ExpireReservationsUseCase expireReservationsUseCase;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PostingRepository postings;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account newAccount(String currency, String balance) {
        return accounts.save(new Account("test-" + UUID.randomUUID(), "Test User", currency, new BigDecimal(balance)));
    }

    @Test
    void conservationInvariantHoldsAfterPostTransfer() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");

        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), amount);
        boolean posted = postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount);

        assertThat(posted).isTrue();
        List<Posting> rows = postings.findAll().stream()
                .filter(p -> p.getTransferId().equals(transferId))
                .collect(Collectors.toList());
        BigDecimal signedSum = rows.stream()
                .map(p -> p.getLeg() == PostingLeg.DEBIT ? p.getAmount().negate() : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(signedSum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void concurrentCheckAndReserveOnlyOneSucceedsWhenFundsCoverJustOne() throws Exception {
        Account account = newAccount("EUR", "100.0000");
        Money amount = Money.of("60.00", "EUR"); // 2 x 60 > 100

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<CheckAndReserveResult> attempt = () -> {
                ready.countDown();
                go.await();
                return checkAndReserveUseCase.checkAndReserve(UUID.randomUUID().toString(), account.getId(), amount);
            };
            Future<CheckAndReserveResult> f1 = pool.submit(attempt);
            Future<CheckAndReserveResult> f2 = pool.submit(attempt);
            ready.await();
            go.countDown();

            List<CheckAndReserveResult> results = List.of(f1.get(), f2.get());
            long okCount = results.stream()
                    .filter(r -> r instanceof CheckAndReserveResult.Ok)
                    .count();
            long insufficientCount = results.stream()
                    .filter(r -> r instanceof CheckAndReserveResult.InsufficientFunds)
                    .count();

            assertThat(okCount).isEqualTo(1);
            assertThat(insufficientCount).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void postTransferIsIdempotentOnSameTransferId() {
        Account from = newAccount("EUR", "50.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("20.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), amount);

        boolean first = postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount);
        boolean second = postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        Account reloaded = accounts.findById(from.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("30.0000");
    }

    @Test
    void balanceNeverGoesNegativeEvenBypassingApplicationLogic() {
        Account account = newAccount("EUR", "10.0000");

        // Bypasses the application guard to test the CHECK constraint alone.
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE accounts SET balance = balance - 100 WHERE id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkAndReserveRejectsCurrencyMismatch() {
        Account account = newAccount("EUR", "100.0000");
        Money usdAmount = Money.of("10.00", "USD");

        assertThatThrownBy(() -> checkAndReserveUseCase.checkAndReserve(
                        UUID.randomUUID().toString(), account.getId(), usdAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void postTransferRejectsCurrencyMismatchOnDestinationAccount() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("USD", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), amount);

        assertThatThrownBy(() -> postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void postTransferWithoutAnyReservationThrows() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");

        assertThatThrownBy(() -> postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount))
                .isInstanceOf(NoMatchingReservationException.class);
        Account reloaded = accounts.findById(from.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void postTransferWithReservationForADifferentAmountThrows() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), Money.of("20.00", "EUR"));

        assertThatThrownBy(() -> postTransferUseCase.postTransfer(
                        transferId, from.getId(), to.getId(), Money.of("30.00", "EUR")))
                .isInstanceOf(NoMatchingReservationException.class);
    }

    @Test
    void checkAndReserveReplayWithDifferentAccountThrowsIdempotencyConflict() {
        Account account = newAccount("EUR", "100.0000");
        Account otherAccount = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);

        assertThatThrownBy(() -> checkAndReserveUseCase.checkAndReserve(transferId, otherAccount.getId(), amount))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void checkAndReserveReplayWithDifferentAmountThrowsIdempotencyConflict() {
        Account account = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), Money.of("30.00", "EUR"));

        assertThatThrownBy(() ->
                        checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), Money.of("31.00", "EUR")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    /** Expiry is forced in the database: nothing in the application ages a
     * reservation. */
    @Test
    void expiredReservationIsExcludedFromHeldAmount() {
        Account account = newAccount("EUR", "200.0000");
        String transferId = UUID.randomUUID().toString();
        checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), Money.of("50.00", "EUR"));
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE transfer_id = ?", transferId);

        WalletSnapshot wallet = getWalletUseCase.getWallet(account.getId());

        assertThat(wallet.held().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.available().amount())
                .isEqualByComparingTo(wallet.balance().amount());
    }

    @Test
    void releaseReservationFreesUpTheHold() {
        Account account = newAccount("EUR", "100.0000");
        Money amount = Money.of("40.00", "EUR");
        String transferId = UUID.randomUUID().toString();
        CheckAndReserveResult result = checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);
        assertThat(result).isInstanceOf(CheckAndReserveResult.Ok.class);
        UUID reservationId = ((CheckAndReserveResult.Ok) result).reservationId();

        releaseReservationUseCase.releaseReservation(transferId, reservationId);

        WalletSnapshot wallet = getWalletUseCase.getWallet(account.getId());
        assertThat(wallet.held().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.available().amount())
                .isEqualByComparingTo(wallet.balance().amount());
    }

    @Test
    void releaseReservationSecondCallIsANoop() {
        Account account = newAccount("EUR", "100.0000");
        Money amount = Money.of("40.00", "EUR");
        String transferId = UUID.randomUUID().toString();
        CheckAndReserveResult result = checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);
        UUID reservationId = ((CheckAndReserveResult.Ok) result).reservationId();

        releaseReservationUseCase.releaseReservation(transferId, reservationId);
        releaseReservationUseCase.releaseReservation(transferId, reservationId);
    }

    @Test
    void releaseReservationOnAnAlreadyReleasedReservationChangesNothing() {
        Account account = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        UUID reservationId = reserve(transferId, account, "40.00");
        releaseReservationUseCase.releaseReservation(transferId, reservationId);

        assertReleaseChangesNothing(transferId, reservationId, account, ReservationStatus.RELEASED);
    }

    @Test
    void releaseReservationOnAnExpiredReservationChangesNothing() {
        Account account = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        UUID reservationId = reserve(transferId, account, "40.00");
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE transfer_id = ?", transferId);
        expireReservationsUseCase.expireOverdueReservations();

        assertReleaseChangesNothing(transferId, reservationId, account, ReservationStatus.EXPIRED);
    }

    @Test
    void releaseReservationOnAConsumedReservationChangesNothing() {
        Account account = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        UUID reservationId = reserve(transferId, account, "40.00");
        postTransferUseCase.postTransfer(transferId, account.getId(), to.getId(), Money.of("40.00", "EUR"));

        assertReleaseChangesNothing(transferId, reservationId, account, ReservationStatus.CONSUMED);
    }

    private UUID reserve(String transferId, Account account, String amount) {
        CheckAndReserveResult result =
                checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), Money.of(amount, "EUR"));
        return ((CheckAndReserveResult.Ok) result).reservationId();
    }

    private void assertReleaseChangesNothing(
            String transferId, UUID reservationId, Account account, ReservationStatus expectedStatus) {
        WalletSnapshot before = getWalletUseCase.getWallet(account.getId());
        assertThat(reservations.findByTransferId(transferId).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);

        releaseReservationUseCase.releaseReservation(transferId, reservationId);

        WalletSnapshot after = getWalletUseCase.getWallet(account.getId());
        assertThat(reservations.findByTransferId(transferId).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);
        assertThat(after.balance().amount())
                .isEqualByComparingTo(before.balance().amount());
        assertThat(after.available().amount())
                .isEqualByComparingTo(before.available().amount());
        assertThat(after.held().amount()).isEqualByComparingTo(before.held().amount());
    }

    @Test
    void getWalletAvailableIsBalanceMinusHeld() {
        Account account = newAccount("EUR", "200.0000");
        Money holdAmount = Money.of("50.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(UUID.randomUUID().toString(), account.getId(), holdAmount);

        WalletSnapshot wallet = getWalletUseCase.getWallet(account.getId());

        assertThat(wallet.balance().amount()).isEqualByComparingTo("200.0000");
        assertThat(wallet.held().amount()).isEqualByComparingTo("50.0000");
        assertThat(wallet.available().amount()).isEqualByComparingTo("150.0000");
    }

    @Test
    void checkAndReserveIsIdempotentOnSameTransferId() {
        Account account = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");

        CheckAndReserveResult first = checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);
        CheckAndReserveResult second = checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);

        assertThat(first).isInstanceOf(CheckAndReserveResult.Ok.class);
        assertThat(second).isInstanceOf(CheckAndReserveResult.Ok.class);
        assertThat(((CheckAndReserveResult.Ok) second).reservationId())
                .isEqualTo(((CheckAndReserveResult.Ok) first).reservationId());
        // Throws if the replay created a second reservation.
        assertThat(reservations.findByTransferId(transferId)).isPresent();
    }

    /** The row lock serializes the two calls in practice; what matters is that both
     * return the same result. */
    @Test
    void checkAndReserveConcurrentCallsWithSameTransferIdConverge() throws Exception {
        Account account = newAccount("EUR", "100.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("30.00", "EUR");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<CheckAndReserveResult> attempt = () -> {
                ready.countDown();
                go.await();
                return checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);
            };
            Future<CheckAndReserveResult> f1 = pool.submit(attempt);
            Future<CheckAndReserveResult> f2 = pool.submit(attempt);
            ready.await();
            go.countDown();

            CheckAndReserveResult r1 = f1.get();
            CheckAndReserveResult r2 = f2.get();

            assertThat(r1).isInstanceOf(CheckAndReserveResult.Ok.class);
            assertThat(((CheckAndReserveResult.Ok) r1).reservationId())
                    .isEqualTo(((CheckAndReserveResult.Ok) r2).reservationId());
            assertThat(reservations.findByTransferId(transferId)).isPresent();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void postTransferReplayWithDifferentDestinationThrowsIdempotencyConflict() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        Account otherTo = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("20.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), amount);
        postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount);

        assertThatThrownBy(() -> postTransferUseCase.postTransfer(transferId, from.getId(), otherTo.getId(), amount))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void checkAndReserveRejectsNonPositiveAmount() {
        Account account = newAccount("EUR", "100.0000");

        assertThatThrownBy(() -> checkAndReserveUseCase.checkAndReserve(
                        UUID.randomUUID().toString(), account.getId(), Money.of("0.00", "EUR")))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> checkAndReserveUseCase.checkAndReserve(
                        UUID.randomUUID().toString(), account.getId(), Money.of("-10.00", "EUR")))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void listPostingsWithZeroLimitReturnsEmptyPageInsteadOfCrashing() {
        Account from = newAccount("EUR", "100.0000");
        Account to = newAccount("EUR", "0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.of("10.00", "EUR");
        checkAndReserveUseCase.checkAndReserve(transferId, from.getId(), amount);
        postTransferUseCase.postTransfer(transferId, from.getId(), to.getId(), amount);

        PostingPage page = listPostingsUseCase.listPostings(from.getId(), null, null, 0);

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void expireOverdueReservationsTransitionsOnlyExpiredActiveOnes() {
        Account account = newAccount("EUR", "200.0000");
        String expiredTransferId = UUID.randomUUID().toString();
        String freshTransferId = UUID.randomUUID().toString();
        checkAndReserveUseCase.checkAndReserve(expiredTransferId, account.getId(), Money.of("30.00", "EUR"));
        checkAndReserveUseCase.checkAndReserve(freshTransferId, account.getId(), Money.of("20.00", "EUR"));
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE transfer_id = ?",
                expiredTransferId);

        int expiredCount = expireReservationsUseCase.expireOverdueReservations();

        // At least one: the sweep is global and may also expire other tests' rows.
        assertThat(expiredCount).isGreaterThanOrEqualTo(1);
        Reservation expired = reservations.findByTransferId(expiredTransferId).orElseThrow();
        Reservation fresh = reservations.findByTransferId(freshTransferId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(fresh.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }
}
