package dev.treyer.sagapay.common.event;

/** The events of {@code payments.transfers}: the orchestrator publishes them, fraud reads them. */
public enum TransferEventType {
    TRANSFER_INITIATED("TransferInitiated"),
    FUNDS_RESERVED("FundsReserved"),
    TRANSFER_POSTED("TransferPosted"),
    TRANSFER_FAILED("TransferFailed"),
    TRANSFER_BLOCKED("TransferBlocked");

    private final String wireName;

    TransferEventType(String wireName) {
        this.wireName = wireName;
    }

    /** The name on the wire, which is part of the event contract. */
    public String wireName() {
        return wireName;
    }
}
