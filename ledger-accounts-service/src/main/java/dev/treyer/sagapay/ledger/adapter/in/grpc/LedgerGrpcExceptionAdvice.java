package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.ledger.domain.CurrencyMismatchException;
import dev.treyer.sagapay.ledger.domain.IdempotencyConflictException;
import dev.treyer.sagapay.ledger.domain.InvalidAmountException;
import dev.treyer.sagapay.ledger.domain.MalformedRequestException;
import dev.treyer.sagapay.ledger.domain.NoMatchingReservationException;
import io.grpc.Status;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;

/** Must be {@code public}: the handler methods are invoked reflectively without
 * {@code setAccessible}. */
@GrpcAdvice
public class LedgerGrpcExceptionAdvice {

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleUnknownAccount(IllegalArgumentException e) {
        return Status.NOT_FOUND.withDescription(e.getMessage());
    }

    @GrpcExceptionHandler(CurrencyMismatchException.class)
    public Status handleCurrencyMismatch(CurrencyMismatchException e) {
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    @GrpcExceptionHandler(InvalidAmountException.class)
    public Status handleInvalidAmount(InvalidAmountException e) {
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    @GrpcExceptionHandler(MalformedRequestException.class)
    public Status handleMalformedRequest(MalformedRequestException e) {
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage());
    }

    /** {@code Money} rejects more than 4 decimals; unhandled, the client would get
     * INTERNAL with an internal message. */
    @GrpcExceptionHandler(ArithmeticException.class)
    public Status handleInvalidPrecision(ArithmeticException e) {
        return Status.INVALID_ARGUMENT.withDescription("invalid amount: " + e.getMessage());
    }

    @GrpcExceptionHandler(NoMatchingReservationException.class)
    public Status handleNoMatchingReservation(NoMatchingReservationException e) {
        return Status.NOT_FOUND.withDescription(e.getMessage());
    }

    @GrpcExceptionHandler(IdempotencyConflictException.class)
    public Status handleIdempotencyConflict(IdempotencyConflictException e) {
        return Status.ALREADY_EXISTS.withDescription(e.getMessage());
    }
}
