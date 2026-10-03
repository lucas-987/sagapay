package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** No active reservation matches the transfer, account and amount: posting would
 * spend funds held for another transfer. */
public class NoMatchingReservationException extends RuntimeException {
    public NoMatchingReservationException(String transferId, UUID accountId, BigDecimal amount) {
        super("no ACTIVE reservation for transferId " + transferId + " matching account " + accountId + " and amount "
                + amount);
    }
}
