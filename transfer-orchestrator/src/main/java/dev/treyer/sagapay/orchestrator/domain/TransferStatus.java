package dev.treyer.sagapay.orchestrator.domain;

/**
 * SCREENING: funds reserved, waiting for the fraud verdict. BLOCKED: flagged, waiting for
 * the sender's confirmation. CLEARED: fraud cleared or sender confirmed, ready to post.
 */
public enum TransferStatus {
    INITIATED,
    RESERVED,
    POSTED,
    FAILED,
    SCREENING,
    CLEARED,
    BLOCKED
}
