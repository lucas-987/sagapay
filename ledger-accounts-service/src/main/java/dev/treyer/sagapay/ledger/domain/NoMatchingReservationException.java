package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code postTransfer} called with no ACTIVE reservation matching this exact
 * {@code (transferId, accountId, amount)}. Without this guard, a debit only checked
 * raw balance, letting an unreserved transfer (or one reserved for a different
 * account/amount) draw on funds already committed to another transfer. Maps to
 * {@code AppErrorCode.RESERVATION_NOT_FOUND} rather than {@code ACCOUNT_NOT_FOUND} —
 * the account exists, it's the expected reservation that doesn't. */
public class NoMatchingReservationException extends RuntimeException {
    public NoMatchingReservationException(String transferId, UUID accountId, BigDecimal amount) {
        super("no ACTIVE reservation for transferId " + transferId + " matching account " + accountId
                + " and amount " + amount);
    }
}
