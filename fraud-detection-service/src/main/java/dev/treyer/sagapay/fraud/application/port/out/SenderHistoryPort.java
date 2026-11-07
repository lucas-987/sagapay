package dev.treyer.sagapay.fraud.application.port.out;

import dev.treyer.sagapay.fraud.domain.SenderActivity;

import java.util.UUID;

public interface SenderHistoryPort {

    SenderActivity read(UUID senderId);

    /** Counting the same transfer again changes nothing. */
    void recordTransfer(UUID senderId, UUID transferId);

    void recordRecipient(UUID senderId, UUID recipientId);
}
