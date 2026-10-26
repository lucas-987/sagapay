package dev.treyer.sagapay.fraud.application.port.out;

import dev.treyer.sagapay.fraud.domain.FraudCase;

public interface FraudCasePort {

    void open(FraudCase fraudCase);
}
