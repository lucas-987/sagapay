package dev.treyer.sagapay.fraud.application.port.out;

import dev.treyer.sagapay.fraud.domain.SenderActivity;

import java.util.UUID;

public interface SenderHistoryPort {

    SenderActivity read(UUID senderId);

    void record(UUID senderId, UUID recipientId);
}
