package dev.treyer.sagapay.orchestrator.domain;

/** The only 3 states practically reachable at the end of M2 (no fraud branch yet):
 * {@code INITIATED} -> {@code RESERVED} -> {@code POSTED}, or {@code FAILED} on
 * insufficient funds. {@code SCREENING}/{@code CLEARED}/{@code BLOCKED}/{@code
 * SETTLED}/{@code REVERSED} from the full spec's TransferStatus are added by
 * M3/M5, when a matching state actually becomes reachable. */
public enum TransferStatus {
    INITIATED,
    RESERVED,
    POSTED,
    FAILED
}
