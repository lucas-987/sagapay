package dev.treyer.sagapay.fraud.application.port.out;

import dev.treyer.sagapay.fraud.domain.OutboxMessage;

public interface OutboxPort {

    void save(OutboxMessage message);
}
