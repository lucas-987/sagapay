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

/** {@code @SpringBootTest} rather than a pure unit test: the {@code FOR UPDATE}
 * lock and the {@code CHECK (balance >= 0)} constraint aren't mockable, a real
 * Postgres is required.
 *
 * <p>Each test creates its own account with a random handle — no shared {@code
 * @Transactional} rollback, which would skew the concurrency tests (both threads
 * must see state that's actually committed, not a suspended test transaction). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.grpc.server.port=0") // ephemeral port: the fixed default would conflict across parallel test contexts
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
        Money amount = Money.of("60.00", "EUR"); // 2x60 > 100 — only one of the two can succeed

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
            long okCount = results.stream().filter(r -> r instanceof CheckAndReserveResult.Ok).count();
            long insufficientCount = results.stream().filter(r -> r instanceof CheckAndReserveResult.InsufficientFunds).count();

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
        assertThat(second).isTrue(); // same memoized result returned, not a second movement
        Account reloaded = accounts.findById(from.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("30.0000");
    }

    @Test
    void balanceNeverGoesNegativeEvenBypassingApplicationLogic() {
        Account account = newAccount("EUR", "10.0000");

        // Deliberately bypasses the application guard (debitIfSufficientFunds) to
        // verify the database CHECK constraint holds on its own.
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

    /** Regression: {@code postTransfer} used to validate only the source account's
     * currency, never the destination's — an EUR→USD transfer silently credited the
     * raw EUR amount onto a USD account. */
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

    /** Regression (real double-spend bug): {@code postTransfer} used to never
     * consult reservations before debiting, only the account's raw balance — an
     * unreserved transfer went through as long as the balance covered it. */
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

    /** Same bug, amount variant: a reservation exists for this transferId but for a
     * different amount, which must not authorize debiting the requested amount. */
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

    /** Regression: a replayed {@code checkAndReserve} on an already-used {@code
     * transferId} used to return the memoized result without ever comparing {@code
     * fromAccountId}/{@code amount} against the original call. */
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

        assertThatThrownBy(() -> checkAndReserveUseCase.checkAndReserve(
                transferId, account.getId(), Money.of("31.00", "EUR")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    /** Regression: {@code heldAmount} used to sum every ACTIVE reservation with no
     * filter on {@code expiresAt} — an abandoned hold (e.g. a crashed client) stayed
     * stuck forever with no sweep job. Expiry is forced directly in the database:
     * nothing in the application code ages a reservation on demand. */
    @Test
    void expiredReservationIsExcludedFromHeldAmount() {
        Account account = newAccount("EUR", "200.0000");
        String transferId = UUID.randomUUID().toString();
        checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), Money.of("50.00", "EUR"));
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE transfer_id = ?",
                transferId);

        WalletSnapshot wallet = getWalletUseCase.getWallet(account.getId());

        assertThat(wallet.held().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.available().amount()).isEqualByComparingTo(wallet.balance().amount());
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
        assertThat(wallet.available().amount()).isEqualByComparingTo(wallet.balance().amount());
    }

    @Test
    void releaseReservationSecondCallIsANoop() {
        Account account = newAccount("EUR", "100.0000");
        Money amount = Money.of("40.00", "EUR");
        String transferId = UUID.randomUUID().toString();
        CheckAndReserveResult result = checkAndReserveUseCase.checkAndReserve(transferId, account.getId(), amount);
        UUID reservationId = ((CheckAndReserveResult.Ok) result).reservationId();

        releaseReservationUseCase.releaseReservation(transferId, reservationId);
        // Must throw nothing: the conditional @Modifying update simply matches no rows the second time.
        releaseReservationUseCase.releaseReservation(transferId, reservationId);
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

    /** Unlike the concurrency test above (two different transferIds), this replays
     * the same transferId sequentially, exercising the "memoized result, read it
     * back" path rather than the funds-check path. */
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
        // findByTransferId's derived query expects at most one row; a non-idempotent
        // replay that created a second reservation would make this call throw instead.
        assertThat(reservations.findByTransferId(transferId)).isPresent();
    }

    /** Same transferId as above, but genuinely concurrent this time. In practice
     * both calls end up serialized by the account's {@code FOR UPDATE} lock (taken
     * before the idempotent write), so the second thread finds the row already
     * committed rather than racing a real INSERT conflict — but the guarantee this
     * test cares about is the observable one: both converge to the same result. */
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

    /** Regression, same bug class as {@code
     * checkAndReserveReplayWithDifferentAccountThrowsIdempotencyConflict}: the
     * idempotent replay of {@code postTransfer} used to only compare the memoized
     * {@code posted} boolean, never {@code fromAccountId}/{@code toAccountId}/{@code
     * amount}. */
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

    /** Regression: a zero or negative amount used to pass the funds-available check
     * (trivially true) and was only rejected on insert by the database's {@code
     * CHECK (amount > 0)} — a 500, not a clean 400. */
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

    /** Regression: {@code limit=0} used to produce an empty {@code items} via
     * {@code subList(0, 0)}, then throw {@code IndexOutOfBoundsException} while
     * computing the next cursor ({@code items.get(-1)}). */
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

    /** Without the scheduled sweep, an abandoned reservation stays {@code ACTIVE} in
     * the database forever, even though {@code heldAmount}/{@code consumeIfMatching}
     * already exclude it via {@code expiresAt}. */
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

        // >= 1, not ==1: the sweep is global (no per-account filter) and shares its
        // Postgres with the rest of the class, so a reservation expired-but-not-swept
        // by another test can get swept here too.
        assertThat(expiredCount).isGreaterThanOrEqualTo(1);
        Reservation expired = reservations.findByTransferId(expiredTransferId).orElseThrow();
        Reservation fresh = reservations.findByTransferId(freshTransferId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(fresh.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    }
}
