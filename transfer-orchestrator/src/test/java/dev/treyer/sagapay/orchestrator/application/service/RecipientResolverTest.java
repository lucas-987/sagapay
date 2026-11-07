package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecipientResolverTest {

    private final AccountLookupPort accounts = mock(AccountLookupPort.class);
    private final RecipientResolver resolver = new RecipientResolver(accounts);

    @ParameterizedTest
    @ValueSource(strings = {"nora", "@nora"})
    void aHandleResolvesWithOrWithoutItsAtSign(String handle) {
        UUID account = UUID.randomUUID();
        when(accounts.lookupByHandle("nora")).thenReturn(Optional.of(account));

        assertThat(resolver.resolveByHandle(handle)).isEqualTo(account);
    }

    @Test
    void anUnknownHandleIsReportedAsTheCallerWroteIt() {
        when(accounts.lookupByHandle("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveByHandle("@nobody"))
                .isInstanceOf(RecipientNotFoundException.class)
                .hasMessage("no such recipient: @nobody");
    }
}
