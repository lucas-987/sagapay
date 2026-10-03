package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.common.error.AppErrorCode;
import dev.treyer.sagapay.common.error.Problems;
import dev.treyer.sagapay.ledger.domain.CurrencyMismatchException;
import dev.treyer.sagapay.ledger.domain.IdempotencyConflictException;
import dev.treyer.sagapay.ledger.domain.InvalidAmountException;
import dev.treyer.sagapay.ledger.domain.InvalidCursorException;
import dev.treyer.sagapay.ledger.domain.MalformedRequestException;
import dev.treyer.sagapay.ledger.domain.NoMatchingReservationException;
import dev.treyer.sagapay.ledger.domain.UnknownHandleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = LedgerRestAdapter.class)
public class LedgerRestExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleUnknownAccount(IllegalArgumentException e) {
        return Problems.of(HttpStatus.NOT_FOUND, AppErrorCode.ACCOUNT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.CURRENCY_MISMATCH, e.getMessage());
    }

    /** Unreachable from the current REST endpoints; mirrors the gRPC advice. */
    @ExceptionHandler(InvalidAmountException.class)
    public ProblemDetail handleInvalidAmount(InvalidAmountException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.INVALID_AMOUNT, e.getMessage());
    }

    /** Unreachable from REST, where Spring MVC binding parses the input; mirrors the
     * gRPC advice. */
    @ExceptionHandler(MalformedRequestException.class)
    public ProblemDetail handleMalformedRequest(MalformedRequestException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.MALFORMED_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoMatchingReservationException.class)
    public ProblemDetail handleNoMatchingReservation(NoMatchingReservationException e) {
        return Problems.of(HttpStatus.NOT_FOUND, AppErrorCode.RESERVATION_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return Problems.of(HttpStatus.CONFLICT, AppErrorCode.DUPLICATE_OPERATION, e.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidCursorException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.INVALID_CURSOR, e.getMessage());
    }

    @ExceptionHandler(UnknownHandleException.class)
    public ProblemDetail handleUnknownHandle(UnknownHandleException e) {
        return Problems.of(HttpStatus.NOT_FOUND, AppErrorCode.RECIPIENT_NOT_FOUND, e.getMessage());
    }
}
