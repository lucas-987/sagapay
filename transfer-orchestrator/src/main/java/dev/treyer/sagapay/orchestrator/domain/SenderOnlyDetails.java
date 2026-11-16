package dev.treyer.sagapay.orchestrator.domain;

import java.util.List;
import java.util.UUID;

/**
 * What only the sender of a transfer is shown, wherever the transfer is read: the cause of a
 * failure, and the saga steps that give it away, could reveal the sender's balance to the
 * recipient. A user who is both parties is the sender.
 */
public final class SenderOnlyDetails {

    private SenderOnlyDetails() {}

    public static FailureReason failureReason(FailureReason reason, UUID senderId, UUID viewer) {
        return senderId.equals(viewer) ? reason : null;
    }

    public static <S> List<S> steps(List<S> steps, UUID senderId, UUID viewer) {
        return senderId.equals(viewer) ? steps : List.of();
    }
}
