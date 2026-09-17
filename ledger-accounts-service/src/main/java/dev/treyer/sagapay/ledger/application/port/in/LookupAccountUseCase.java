package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.ledger.domain.AccountLookup;

public interface LookupAccountUseCase {
    AccountLookup lookupByHandle(String handle);
}
