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
import dev.treyer.sagapay.ledger.domain.LedgerIdempotencyId;
import dev.treyer.sagapay.ledger.domain.LedgerOperation;
import dev.treyer.sagapay.ledger.domain.NoMatchingReservationException;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;
import dev.treyer.sagapay.ledger.domain.PostingLeg;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
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

@Service
public class LedgerService
        implements CheckAndReserveUseCase,
                PostTransferUseCase,
                ReleaseReservationUseCase,
                GetBalanceUseCase,
                GetWalletUseCase,
                ListPostingsUseCase,
                LookupAccountUseCase,
                ExpireReservationsUseCase {

    private static final long RESERVATION_TTL_MINUTES = 5;

    private final AccountPort accounts;
    private final ReservationPort reservations;
    private final PostingPort postings;
    private final LedgerIdempotencyPort idempotency;
    private final JsonMapper jsonMapper;

    public LedgerService(
            AccountPort accounts,
            ReservationPort reservations,
            PostingPort postings,
            LedgerIdempotencyPort idempotency,
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
        // The lock serializes replays of the same transferId, so the result can be
        // computed before the idempotent write.
        Account account = accounts.findByIdForUpdate(fromAccountId)
                .orElseThrow(() -> new IllegalArgumentException("unknown account " + fromAccountId));

        requireSameCurrency(account, amount);
        BigDecimal requestedAmount = amount.amount();
        if (requestedAmount.signum() <= 0) {
            // A non-positive amount would pass the funds check and only fail on the
            // table's CHECK constraint, as a 500.
            throw new InvalidAmountException(requestedAmount);
        }

        BigDecimal held = heldAmount(fromAccountId);
        BigDecimal available = account.getBalance().subtract(held);

        UUID reservationId = UUID.randomUUID();
        CheckAndReserveResult result = available.compareTo(requestedAmount) >= 0
                ? new CheckAndReserveResult.Ok(reservationId)
                : new CheckAndReserveResult.InsufficientFunds();

        int inserted = idempotency.insertIfAbsent(
                transferId, LedgerOperation.RESERVE.name(), toResultJson(result, fromAccountId, requestedAmount));
        if (inserted == 0) {
            // Safety net behind the lock: a concurrent replay stored its result
            // first. Return it only if it was the same call.
            ResultJson previous = jsonMapper.readValue(
                    idempotency
                            .findById(new LedgerIdempotencyId(transferId, LedgerOperation.RESERVE))
                            .orElseThrow(() -> new IllegalStateException("idempotency row vanished for " + transferId))
                            .getResultJson(),
                    ResultJson.class);
            if (!previous.fromAccountId().equals(fromAccountId)
                    || previous.amount().compareTo(requestedAmount) != 0) {
                throw new IdempotencyConflictException(transferId, fromAccountId, requestedAmount);
            }
            return toCheckAndReserveResult(previous);
        }

        if (result instanceof CheckAndReserveResult.Ok) {
            reservations.save(new Reservation(
                    reservationId,
                    fromAccountId,
                    transferId,
                    requestedAmount,
                    Instant.now().plus(RESERVATION_TTL_MINUTES, ChronoUnit.MINUTES)));
        }
        return result;
    }

    // Idempotence is checked before mutating, unlike checkAndReserve: here computing
    // the result is the mutation itself.
    @Override
    @Transactional
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        BigDecimal requestedAmount = amount.amount();
        Optional<Boolean> memoized = existingPostResult(transferId, fromAccountId, toAccountId, requestedAmount);
        if (memoized.isPresent()) {
            return memoized.get();
        }

        // Locks taken in ascending id order, so two opposite transfers cannot
        // deadlock.
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

        requireSameCurrency(fromAccount, amount);
        requireSameCurrency(toAccount, amount);

        // A concurrent replay may have committed since the lock-free check.
        memoized = existingPostResult(transferId, fromAccountId, toAccountId, requestedAmount);
        if (memoized.isPresent()) {
            return memoized.get();
        }

        int consumed = reservations.consumeIfMatching(
                transferId, fromAccountId, requestedAmount, ReservationStatus.ACTIVE, ReservationStatus.CONSUMED);
        if (consumed == 0) {
            throw new NoMatchingReservationException(transferId, fromAccountId, requestedAmount);
        }

        boolean posted = accounts.debitIfSufficientFunds(fromAccountId, requestedAmount) > 0;
        if (!posted) {
            // The reservation guaranteed the funds. Failing the transaction also
            // undoes the reservation consumption above.
            throw new IllegalStateException("reservation consumed but debit failed for transferId " + transferId
                    + " on account " + fromAccountId);
        }
        accounts.credit(toAccountId, requestedAmount);
        UUID entryGroup = UUID.randomUUID();
        postings.save(new Posting(entryGroup, fromAccountId, transferId, PostingLeg.DEBIT, requestedAmount));
        postings.save(new Posting(entryGroup, toAccountId, transferId, PostingLeg.CREDIT, requestedAmount));
        idempotency.insertIfAbsent(
                transferId,
                LedgerOperation.POST.name(),
                toPostResultJson(posted, fromAccountId, toAccountId, requestedAmount));
        return posted;
    }

    @Override
    @Transactional
    public void releaseReservation(String transferId, UUID reservationId) {
        reservations.updateStatusByReservationId(
                transferId, reservationId, ReservationStatus.ACTIVE, ReservationStatus.RELEASED);
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

    // No lock: a slightly stale hold is acceptable for a displayed balance.
    @Override
    @Transactional(readOnly = true)
    public WalletSnapshot getWallet(UUID accountId) {
        Account account = requireAccount(accountId);
        BigDecimal held = heldAmount(accountId);
        BigDecimal available = account.getBalance().subtract(held);
        String currency = account.getCurrency();
        return new WalletSnapshot(
                Money.of(account.getBalance(), currency), Money.of(available, currency), Money.of(held, currency));
    }

    // Fetches limit + 1 rows: the extra one only signals a next page.
    @Override
    @Transactional(readOnly = true)
    public PostingPage listPostings(UUID accountId, Instant from, PostingCursor after, int limit) {
        Account account = requireAccount(accountId);
        if (limit <= 0) {
            // The generated interface does not enforce the OpenAPI minimum of 1.
            return new PostingPage(List.of(), account.getCurrency(), null);
        }
        List<Posting> rows = postings.findPage(accountId, from, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Posting> items = hasMore ? rows.subList(0, limit) : rows;
        PostingCursor next = hasMore ? PostingCursor.of(items.get(items.size() - 1)) : null;
        return new PostingPage(items, account.getCurrency(), next);
    }

    // Never exposes the balance: this answers "who is this" before sending money.
    @Override
    @Transactional(readOnly = true)
    public AccountLookup lookupByHandle(String handle) {
        Account account = accounts.findByHandle(handle).orElseThrow(() -> new UnknownHandleException(handle));
        return new AccountLookup(account.getId(), account.getDisplayName());
    }

    private Account requireAccount(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("unknown account " + accountId));
    }

    private BigDecimal heldAmount(UUID accountId) {
        return reservations.sumAmountByAccountIdAndStatus(accountId, ReservationStatus.ACTIVE);
    }

    private static void requireSameCurrency(Account account, Money amount) {
        if (!amount.hasCurrencyCode(account.getCurrency())) {
            throw new CurrencyMismatchException("currency mismatch: account " + account.getId() + " is "
                    + account.getCurrency() + ", amount is " + amount.currency().getCurrencyCode());
        }
    }

    private Optional<Boolean> existingPostResult(
            String transferId, UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        Optional<PostResultJson> previous = idempotency
                .findById(new LedgerIdempotencyId(transferId, LedgerOperation.POST))
                .map(row -> jsonMapper.readValue(row.getResultJson(), PostResultJson.class));
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        PostResultJson dto = previous.get();
        if (!dto.fromAccountId().equals(fromAccountId)
                || !dto.toAccountId().equals(toAccountId)
                || dto.amount().compareTo(amount) != 0) {
            throw new IdempotencyConflictException(transferId, fromAccountId, amount);
        }
        return Optional.of(dto.posted());
    }

    // Flat records rather than the sealed result type: no polymorphic Jackson setup.
    // They keep the call's parameters so a replay can be checked against them.

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
