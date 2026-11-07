package dev.treyer.sagapay.fraud.domain;

/** The events of {@code payments.fraud.results}. */
public enum FraudResultEventType {
    TRANSFER_CLEARED("TransferCleared"),
    TRANSFER_FLAGGED("TransferFlagged");

    private final String wireName;

    FraudResultEventType(String wireName) {
        this.wireName = wireName;
    }

    /** The name on the wire, which is part of the event contract. */
    public String wireName() {
        return wireName;
    }
}
