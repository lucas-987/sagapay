package dev.treyer.sagapay.common.error;

/** Application error codes, carried in the "code" field of the ProblemDetail. */
public enum AppErrorCode {
    INSUFFICIENT_FUNDS,
    RECIPIENT_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    RESERVATION_NOT_FOUND,
    CURRENCY_MISMATCH,
    INVALID_AMOUNT,
    MALFORMED_REQUEST,
    DUPLICATE_OPERATION,
    TRANSFER_NOT_FOUND,
    TRANSFER_NOT_BLOCKED,
    VERIFICATION_EXPIRED,
    INVALID_VERIFICATION_CODE,
    LEDGER_UNAVAILABLE,
    INVALID_CURSOR
}
