package dev.treyer.sagapay.common.event;

/** The events of {@code payments.transfers}: the orchestrator publishes them, fraud reads them. */
public enum TransferEventType {
    TRANSFER_INITIATED("TransferInitiated"),
    FUNDS_RESERVED("FundsReserved"),
    TRANSFER_POSTED("TransferPosted"),
    TRANSFER_FAILED("TransferFailed"),
    TRANSFER_BLOCKED("TransferBlocked");

    private static final String VERSION = ".v1";

    private final String wireName;

    TransferEventType(String wireName) {
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
