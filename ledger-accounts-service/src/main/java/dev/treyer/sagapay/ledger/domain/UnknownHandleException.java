package dev.treyer.sagapay.ledger.domain;

/** Unknown {@code handle} for a {@code GET /v1/users/lookup} — distinct from {@code
 * IllegalArgumentException} (same reasoning as {@link InvalidCursorException}) so it
 * maps to {@code AppErrorCode.RECIPIENT_NOT_FOUND} rather than {@code
 * ACCOUNT_NOT_FOUND}: same 404, but "this handle matches no one" is a different
 * business message than "this account doesn't exist". */
public class UnknownHandleException extends RuntimeException {
    public UnknownHandleException(String handle) {
        super("unknown handle " + handle);
    }
}
