package dev.treyer.sagapay.fraud.domain;

/** The events of {@code payments.fraud.results}. */
public enum FraudResultEventType {
    TRANSFER_CLEARED("TransferCleared"),
    TRANSFER_FLAGGED("TransferFlagged");

    private static final String VERSION = ".v1";

    private final String wireName;

    FraudResultEventType(String wireName) {
        this.wireName = wireName;
    }

    /** The name on the wire, which is part of the event contract. */
    public String wireName() {
        return wireName;
    }

    /** The CloudEvent {@code type} of this event: its name and the version of its contract. */
    public String cloudEventType() {
        return cloudEventType(wireName);
    }

    /** The CloudEvent {@code type} of an event stored by its name, as the outbox stores it. */
    public static String cloudEventType(String wireName) {
        return wireName + VERSION;
    }
}
