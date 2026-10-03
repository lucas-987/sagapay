package dev.treyer.sagapay.orchestrator.domain;

/** Only the states reachable without fraud screening. */
public enum TransferStatus {
    INITIATED,
    RESERVED,
    POSTED,
    FAILED
}
