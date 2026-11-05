package dev.treyer.sagapay.fraud.application.port.in;

import java.util.UUID;

public interface RecordPostedTransferUseCase {

    /** Safe to call again with the same transfer. */
    void recordPosted(UUID senderId, UUID recipientId);
}
