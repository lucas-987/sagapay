package dev.treyer.sagapay.orchestrator.domain;

/** Why a transfer ended {@code FAILED}; also carried by its {@code TransferFailed} event. */
public enum FailureReason {
    INSUFFICIENT_FUNDS,
    RESERVE_REJECTED,
    POST_REJECTED,
    SCREENING_TIMEOUT,
    VERIFICATION_EXPIRED
}
