package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.ledger.domain.AccountLookup;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LookupAccountNamesUseCase {
    /** One entry per known id; an unknown id is left out. */
    List<AccountLookup> lookupNames(Set<UUID> accountIds);
}
