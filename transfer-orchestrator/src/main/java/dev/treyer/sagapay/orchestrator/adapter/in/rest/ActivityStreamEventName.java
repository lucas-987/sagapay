package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;

/** The event names of {@code activity-stream.openapi.yaml}, one per outcome pushed. */
enum ActivityStreamEventName {
    TRANSFER_POSTED("transfer.posted"),
    TRANSFER_BLOCKED("transfer.blocked"),
    TRANSFER_FAILED("transfer.failed");

    private final String wireName;

    ActivityStreamEventName(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }

    static ActivityStreamEventName of(TransferEvent.Outcome outcome) {
        return switch (outcome) {
            case POSTED -> TRANSFER_POSTED;
            case BLOCKED -> TRANSFER_BLOCKED;
            case FAILED -> TRANSFER_FAILED;
        };
    }
}
