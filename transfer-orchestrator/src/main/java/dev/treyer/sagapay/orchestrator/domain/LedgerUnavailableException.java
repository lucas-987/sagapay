package dev.treyer.sagapay.orchestrator.domain;

/** Thrown by {@code LedgerGrpcClientAdapter}'s circuit-breaker fallback, on both
 * an individual call failure (timeout, connection error) and once the breaker
 * itself is open ({@code CallNotPermittedException}) — the caller (REST
 * adapter, §8) can't tell those apart and doesn't need to; both mean "the
 * ledger isn't answering right now". */
public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(Throwable cause) {
        super("ledger unavailable", cause);
    }
}
