package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.FraudResult;

public interface ApplyFraudResultUseCase {

    /** Safe to call again with the same result: it is applied once. */
    void applyFraudResult(FraudResult result);
}
