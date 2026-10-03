package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.error.AppErrorCode;
import dev.treyer.sagapay.common.error.Problems;
import dev.treyer.sagapay.orchestrator.domain.InvalidCursorException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.MalformedRequestException;
import dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotBlockedException;
import dev.treyer.sagapay.orchestrator.domain.TransferNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = TransferRestAdapter.class)
public class TransferRestExceptionHandler {

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException e) {
        return Problems.of(HttpStatus.NOT_FOUND, AppErrorCode.TRANSFER_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TransferNotBlockedException.class)
    public ProblemDetail handleTransferNotBlocked(TransferNotBlockedException e) {
        return Problems.of(HttpStatus.CONFLICT, AppErrorCode.TRANSFER_NOT_BLOCKED, e.getMessage());
    }

    @ExceptionHandler(RecipientNotFoundException.class)
    public ProblemDetail handleRecipientNotFound(RecipientNotFoundException e) {
        return Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, AppErrorCode.RECIPIENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MalformedRequestException.class)
    public ProblemDetail handleMalformedRequest(MalformedRequestException e) {
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
}
