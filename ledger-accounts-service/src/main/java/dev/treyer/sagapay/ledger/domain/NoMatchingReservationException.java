package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** No active reservation matches the transfer, accounts and amount: posting would
 * spend funds held for another transfer or another recipient. */
public class NoMatchingReservationException extends RuntimeException {
    public NoMatchingReservationException(String transferId, UUID accountId, UUID toAccountId, BigDecimal amount) {
        super("no ACTIVE reservation for transferId " + transferId + " matching account " + accountId + ", recipient "
                + toAccountId + " and amount " + amount);
    }
}
