package dev.treyer.sagapay.orchestrator.application.port.out;

import dev.treyer.sagapay.orchestrator.domain.OutboxRow;

public interface OutboxPort {

    void save(OutboxRow row);
}
