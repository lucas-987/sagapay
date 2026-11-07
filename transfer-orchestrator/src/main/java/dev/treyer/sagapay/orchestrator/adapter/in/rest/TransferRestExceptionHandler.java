package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.error.AppErrorCode;
import dev.treyer.sagapay.common.error.Problems;
import dev.treyer.sagapay.orchestrator.domain.InvalidCursorException;
import dev.treyer.sagapay.orchestrator.domain.InvalidVerificationCodeException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.MalformedRequestException;
import dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.VerificationExpiredException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The parent class answers the errors Spring detects itself: without it the catch-all below would
 * turn them, a missing parameter for instance, into a 500. */
@RestControllerAdvice(basePackageClasses = TransferRestAdapter.class)
public class TransferRestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TransferRestExceptionHandler.class);

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, AppErrorCode.TRANSFER_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TransferNotBlockedException.class)
    public ProblemDetail handleTransferNotBlocked(TransferNotBlockedException e) {
        return Problems.of(HttpStatus.CONFLICT, AppErrorCode.TRANSFER_NOT_BLOCKED, e.getMessage());
    }

    @ExceptionHandler(VerificationExpiredException.class)
    public ProblemDetail handleVerificationExpired(VerificationExpiredException e) {
        return Problems.of(HttpStatus.GONE, AppErrorCode.VERIFICATION_EXPIRED, e.getMessage());
    }

    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ProblemDetail handleInvalidVerificationCode(InvalidVerificationCodeException e) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, AppErrorCode.INVALID_VERIFICATION_CODE, e.getMessage());
    }

    @ExceptionHandler(RecipientNotFoundException.class)
    public ProblemDetail handleRecipientNotFound(RecipientNotFoundException e) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, AppErrorCode.RECIPIENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MalformedRequestException.class)
    public ProblemDetail handleMalformedRequest(MalformedRequestException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.MALFORMED_REQUEST, e.getMessage());
    }

    /** The bounds the contract declares on a parameter, applied by the generated interface. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.MALFORMED_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidCursorException e) {
        return Problems.of(HttpStatus.BAD_REQUEST, AppErrorCode.INVALID_CURSOR, e.getMessage());
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    public ProblemDetail handleLedgerUnavailable(LedgerUnavailableException e) {
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, AppErrorCode.LEDGER_UNAVAILABLE, e.getMessage());
    }

    /** The message stays in the log: it may name internals the caller has no use for. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("request failed unexpectedly", e);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, AppErrorCode.INTERNAL_ERROR, "unexpected error");
    }
}
