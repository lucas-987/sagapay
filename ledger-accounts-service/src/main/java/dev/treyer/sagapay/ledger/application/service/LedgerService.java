package dev.treyer.sagapay.ledger.application.service;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.application.port.in.CheckAndReserveUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ExpireReservationsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.GetBalanceUseCase;
import dev.treyer.sagapay.ledger.application.port.in.GetWalletUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ListPostingsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.LookupAccountUseCase;
import dev.treyer.sagapay.ledger.application.port.in.PostTransferUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ReleaseReservationUseCase;
import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.application.port.out.LedgerIdempotencyPort;
import dev.treyer.sagapay.ledger.application.port.out.PostingPort;
import dev.treyer.sagapay.ledger.application.port.out.ReservationPort;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.domain.AccountLookup;
import dev.treyer.sagapay.ledger.domain.CheckAndReserveResult;
import dev.treyer.sagapay.ledger.domain.CurrencyMismatchException;
import dev.treyer.sagapay.ledger.domain.IdempotencyConflictException;
import dev.treyer.sagapay.ledger.domain.InvalidAmountException;
import dev.treyer.sagapay.ledger.domain.LedgerOperation;
import dev.treyer.sagapay.ledger.domain.NoMatchingReservationException;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;
import dev.treyer.sagapay.ledger.domain.PostingLeg;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import dev.treyer.sagapay.ledger.domain.UnknownHandleException;
import dev.treyer.sagapay.ledger.domain.WalletSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One class for every use case, rather than one class per use case: they share the
 * same out ports and operate on the same aggregate (account + its reservations), so
 * splitting further wouldn't add anything beyond the interface abstraction already
 * in place. Each method is a single local transaction — no distributed transaction.
 */
@Service
public class LedgerService implements CheckAndReserveUseCase, PostTransferUseCase,
        ReleaseReservationUseCase, GetBalanceUseCase, GetWalletUseCase, ListPostingsUseCase,
        LookupAccountUseCase, ExpireReservationsUseCase {

    private static final long RESERVATION_TTL_MINUTES = 5;

    private final AccountPort accounts;
    private final ReservationPort reservations;
    private final PostingPort postings;
    private final LedgerIdempotencyPort idempotency;
    private final JsonMapper jsonMapper;

    public LedgerService(AccountPort accounts, ReservationPort reservations,
                          PostingPort postings, LedgerIdempotencyPort idempotency,
                          JsonMapper jsonMapper) {
        this.accounts = accounts;
        this.reservations = reservations;
        this.postings = postings;
        this.idempotency = idempotency;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public CheckAndReserveResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        // Pessimistic lock: serializes concurrent replays of the same transferId so
        // everything below can be computed before the idempotent write.
        Account account = accounts.findByIdForUpdate(fromAccountId)
                .orElseThrow(() -> new IllegalArgumentException("unknown account " + fromAccountId));

        requireSameCurrency(account, amount);
        BigDecimal requestedAmount = amount.amount();
        if (requestedAmount.signum() <= 0) {
            // Without this guard a zero/negative amount passed the funds-available
            // check below (trivially true) and was only rejected by the
            // reservations table's CHECK (amount > 0) — a 500 with a leaked
            // persistence stack trace instead of a clean 400.
            throw new InvalidAmountException(requestedAmount);
        }

        BigDecimal held = heldAmount(fromAccountId);
        BigDecimal available = account.getBalance().subtract(held);

        // Reused for both the returned result and the persisted row below — not two
        // independent UUID.randomUUID() calls. A prior bug generated them
        // separately, so the id returned to the caller never matched the persisted
        // reservation and releaseReservation never released anything.
        UUID reservationId = UUID.randomUUID();
        CheckAndReserveResult result = available.compareTo(requestedAmount) >= 0
                ? new CheckAndReserveResult.Ok(reservationId)
                : new CheckAndReserveResult.InsufficientFunds();

        int inserted = idempotency.insertIfAbsent(transferId, LedgerOperation.RESERVE.name(),
                toResultJson(result, fromAccountId, requestedAmount));
        if (inserted == 0) {
            // A concurrent replay won the race (shouldn't happen given the lock
            // above, but stays as a safety net): discard our own computation and
            // read back the definitive result — but only if it's really the same
            // call (same fromAccountId/amount), not a transferId reused for a
            // different request.
            ResultJson previous = jsonMapper.readValue(idempotency
                    .findById(new LedgerIdempotencyId(transferId, LedgerOperation.RESERVE))
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished for " + transferId))
                    .getResultJson(), ResultJson.class);
            if (!previous.fromAccountId().equals(fromAccountId) || previous.amount().compareTo(requestedAmount) != 0) {
                throw new IdempotencyConflictException(transferId, fromAccountId, requestedAmount);
            }
            return toCheckAndReserveResult(previous);
        }

        if (result instanceof CheckAndReserveResult.Ok) {
            reservations.save(new Reservation(reservationId, fromAccountId, transferId, requestedAmount,
                    Instant.now().plus(RESERVATION_TTL_MINUTES, ChronoUnit.MINUTES)));
        }
        return result;
    }

    /**
     * Unlike {@link #checkAndReserve}, idempotence is checked before mutating
     * anything, not after computing the result: here the computation
     * ({@code debitIfSufficientFunds}) IS the mutation, so there's no way to defer
     * the idempotent write behind the computation.
     */
    @Override
    @Transactional
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        BigDecimal requestedAmount = amount.amount();
        Optional<Boolean> memoized = existingPostResult(transferId, fromAccountId, toAccountId, requestedAmount);
        if (memoized.isPresent()) {
            return memoized.get();
        }

        // Lock ordered by ascending id to avoid deadlocking against a concurrent
        // transfer in the opposite direction; this also serializes concurrent
        // replays of the same transferId, as in checkAndReserve.
        Account fromAccount = null;
        Account toAccount = null;
        for (UUID id : Stream.of(fromAccountId, toAccountId).sorted().toList()) {
            Account locked = accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("unknown account " + id));
            if (id.equals(fromAccountId)) {
                fromAccount = locked;
            }
            if (id.equals(toAccountId)) {
                toAccount = locked;
            }
        }
        // Both accounts, not just the source: otherwise an EUR->USD transfer would
        // pass the guard on the source side (the amount is indeed EUR) then
        // silently credit the raw EUR amount onto a USD account.
        requireSameCurrency(fromAccount, amount);
        requireSameCurrency(toAccount, amount);

        // Re-check now that the lock is held: a concurrent replay could have
        // committed between the first (lock-free) check above and here.
        memoized = existingPostResult(transferId, fromAccountId, toAccountId, requestedAmount);
        if (memoized.isPresent()) {
            return memoized.get();
        }

        // Without this, debitIfSufficientFunds below only looks at the account's
        // raw balance, never at reservations: an unreserved transfer could still
        // debit as long as the raw balance covered it, drawing on funds already
        // committed to another currently-active transfer.
        int consumed = reservations.consumeIfMatching(transferId, fromAccountId, requestedAmount,
                ReservationStatus.ACTIVE, ReservationStatus.CONSUMED);
        if (consumed == 0) {
            throw new NoMatchingReservationException(transferId, fromAccountId, requestedAmount);
        }

        boolean posted = accounts.debitIfSufficientFunds(fromAccountId, requestedAmount) > 0;
        if (!posted) {
            // Should never happen: checkAndReserve already guaranteed the balance
            // covered this reservation, and the account has stayed locked since.
            // If it happens anyway, fail the whole transaction (undoing the
            // reservation consumption above too) rather than memoize
            // "posted=false" when the reservation was just marked consumed.
            throw new IllegalStateException(
                    "reservation consumed but debit failed for transferId " + transferId
                            + " on account " + fromAccountId);
        }
        accounts.credit(toAccountId, requestedAmount);
        UUID entryGroup = UUID.randomUUID();
        postings.save(new Posting(entryGroup, fromAccountId, transferId, PostingLeg.DEBIT, requestedAmount));
        postings.save(new Posting(entryGroup, toAccountId, transferId, PostingLeg.CREDIT, requestedAmount));
        idempotency.insertIfAbsent(transferId, LedgerOperation.POST.name(),
                toPostResultJson(posted, fromAccountId, toAccountId, requestedAmount));
        return posted;
    }

    @Override
    @Transactional
    public void releaseReservation(String transferId, UUID reservationId) {
        reservations.updateStatusByReservationId(transferId, reservationId,
                ReservationStatus.ACTIVE, ReservationStatus.RELEASED);
    }

    @Override
    @Transactional
    public int expireOverdueReservations() {
        return reservations.expireOverdue();
    }

    @Override
    @Transactional(readOnly = true)
    public Money getBalance(UUID accountId) {
        Account account = requireAccount(accountId);
        return Money.of(account.getBalance(), account.getCurrency());
    }

    /** Read-only, without a lock: a dirty read of a hold that was just released is
     * acceptable for a displayed balance, unlike {@link #checkAndReserve}. */
    @Override
    @Transactional(readOnly = true)
    public WalletSnapshot getWallet(UUID accountId) {
        Account account = requireAccount(accountId);
        BigDecimal held = heldAmount(accountId);
        BigDecimal available = account.getBalance().subtract(held);
        String currency = account.getCurrency();
        return new WalletSnapshot(
                Money.of(account.getBalance(), currency),
                Money.of(available, currency),
                Money.of(held, currency));
    }

    /** Requests {@code limit + 1} rows from the port: the extra row (if present)
     * only tells us whether a next page exists, and is stripped before returning
     * {@code items}. */
    @Override
    @Transactional(readOnly = true)
    public PostingPage listPostings(UUID accountId, Instant from, PostingCursor after, int limit) {
        Account account = requireAccount(accountId);
        if (limit <= 0) {
            // The OpenAPI spec declares `minimum: 1`, but the generated interface
            // doesn't enforce it server-side — without this guard, limit=0 produced
            // an empty `items` then an IndexOutOfBoundsException while computing the
            // next cursor. Treated as "no items requested", not an error.
            return new PostingPage(List.of(), account.getCurrency(), null);
        }
        List<Posting> rows = postings.findPage(accountId, from, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Posting> items = hasMore ? rows.subList(0, limit) : rows;
        PostingCursor next = hasMore ? PostingCursor.of(items.get(items.size() - 1)) : null;
        return new PostingPage(items, account.getCurrency(), next);
    }

    /** Returns only {@code accountId}/{@code displayName} — {@code requireAccount}
     * would return the full {@link Account}, balance included, which a "who is
     * this" lookup before sending money must never expose. */
    @Override
    @Transactional(readOnly = true)
    public AccountLookup lookupByHandle(String handle) {
        Account account = accounts.findByHandle(handle)
                .orElseThrow(() -> new UnknownHandleException(handle));
        return new AccountLookup(account.getId(), account.getDisplayName());
    }

    /** {@code IllegalArgumentException} maps to 404/{@code NOT_FOUND} in both
     * adapters — see {@code LedgerRestExceptionHandler}/{@code LedgerGrpcAdapter}. */
    private Account requireAccount(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("unknown account " + accountId));
    }

    private BigDecimal heldAmount(UUID accountId) {
        return reservations.sumAmountByAccountIdAndStatus(accountId, ReservationStatus.ACTIVE);
    }

    /** Rejects an inconsistent currency rather than silently treating it as the
     * account's own — no multi-currency conversion. */
    private static void requireSameCurrency(Account account, Money amount) {
        if (!amount.hasCurrencyCode(account.getCurrency())) {
            // Dedicated exception, not a bare IllegalArgumentException: this must
            // map to 400/INVALID_ARGUMENT, not the 404/NOT_FOUND used for an
            // unknown account.
            throw new CurrencyMismatchException("currency mismatch: account " + account.getId()
                    + " is " + account.getCurrency() + ", amount is " + amount.currency().getCurrencyCode());
        }
    }

    /** Throws {@link IdempotencyConflictException} if a replay's parameters don't
     * match the original call, same guard as {@link #checkAndReserve}. */
    private Optional<Boolean> existingPostResult(String transferId, UUID fromAccountId, UUID toAccountId,
                                                  BigDecimal amount) {
        Optional<PostResultJson> previous = idempotency
                .findById(new LedgerIdempotencyId(transferId, LedgerOperation.POST))
                .map(row -> jsonMapper.readValue(row.getResultJson(), PostResultJson.class));
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        PostResultJson dto = previous.get();
        if (!dto.fromAccountId().equals(fromAccountId) || !dto.toAccountId().equals(toAccountId)
                || dto.amount().compareTo(amount) != 0) {
            throw new IdempotencyConflictException(transferId, fromAccountId, amount);
        }
        return Optional.of(dto.posted());
    }

    // Each operation serializes a small flat DTO record rather than the
    // CheckAndReserveResult sealed interface directly, avoiding any polymorphic
    // Jackson config for a shape known ahead of time. JacksonException is unchecked
    // in Jackson 3, so no try/catch is needed. Each DTO also carries the original
    // call's parameters alongside the result, so a replay can be validated against
    // the original call before its memoized result is returned.

    private record ResultJson(String status, UUID reservationId, UUID fromAccountId, BigDecimal amount) {}

    private record PostResultJson(boolean posted, UUID fromAccountId, UUID toAccountId, BigDecimal amount) {}

    private String toResultJson(CheckAndReserveResult result, UUID fromAccountId, BigDecimal amount) {
        ResultJson dto = switch (result) {
            case CheckAndReserveResult.Ok ok -> new ResultJson("OK", ok.reservationId(), fromAccountId, amount);
            case CheckAndReserveResult.InsufficientFunds ignored ->
                    new ResultJson("INSUFFICIENT_FUNDS", null, fromAccountId, amount);
        };
        return jsonMapper.writeValueAsString(dto);
    }

    private static CheckAndReserveResult toCheckAndReserveResult(ResultJson dto) {
        return "OK".equals(dto.status())
                ? new CheckAndReserveResult.Ok(dto.reservationId())
                : new CheckAndReserveResult.InsufficientFunds();
    }

    private String toPostResultJson(boolean posted, UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        return jsonMapper.writeValueAsString(new PostResultJson(posted, fromAccountId, toAccountId, amount));
    }
}
