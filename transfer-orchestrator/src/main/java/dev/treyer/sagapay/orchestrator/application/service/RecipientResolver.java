package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.ResolveRecipientUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
class RecipientResolver implements ResolveRecipientUseCase {

    private final AccountLookupPort accounts;

    RecipientResolver(AccountLookupPort accounts) {
        this.accounts = accounts;
    }

    @Override
    public UUID resolveByHandle(String handle) {
        String bareHandle = handle.startsWith("@") ? handle.substring(1) : handle;
        return accounts.lookupByHandle(bareHandle).orElseThrow(() -> new RecipientNotFoundException(handle));
    }
}
