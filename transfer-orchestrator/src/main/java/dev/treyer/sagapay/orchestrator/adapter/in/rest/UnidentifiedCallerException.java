package dev.treyer.sagapay.orchestrator.adapter.in.rest;

/** The request carries no usable identity: no validated token, or a subject that is not an account id. */
class UnidentifiedCallerException extends RuntimeException {

    UnidentifiedCallerException(String reason) {
        super(reason);
    }

    UnidentifiedCallerException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
