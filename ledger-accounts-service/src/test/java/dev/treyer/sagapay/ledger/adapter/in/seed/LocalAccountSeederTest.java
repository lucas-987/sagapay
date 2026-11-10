package dev.treyer.sagapay.ledger.adapter.in.seed;

import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.domain.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAccountSeederTest {

    private static final Map<String, String> EXPECTED_IDS = expectedIds();

    private static Map<String, String> expectedIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("alice", "00000000-0000-4000-a000-000000000001");
        ids.put("bob", "00000000-0000-4000-a000-000000000002");
        ids.put("chloe", "00000000-0000-4000-a000-000000000003");
        ids.put("david", "00000000-0000-4000-a000-000000000004");
        ids.put("emma", "00000000-0000-4000-a000-000000000005");
        ids.put("piotr", "00000000-0000-4000-a000-000000000006");
        ids.put("gina", "00000000-0000-4000-a000-000000000007");
        ids.put("hugo", "00000000-0000-4000-a000-000000000008");
        ids.put("ines", "00000000-0000-4000-a000-000000000009");
        ids.put("julien", "00000000-0000-4000-a000-000000000010");
        ids.put("wojciech", "00000000-0000-4000-a000-000000000011");
        ids.put("lina", "00000000-0000-4000-a000-000000000012");
        ids.put("marco", "00000000-0000-4000-a000-000000000013");
        ids.put("nora", "00000000-0000-4000-a000-000000000014");
        ids.put("tomasz", "00000000-0000-4000-a000-000000000015");
        ids.put("priya", "00000000-0000-4000-a000-000000000016");
        ids.put("quentin", "00000000-0000-4000-a000-000000000017");
        ids.put("rosa", "00000000-0000-4000-a000-000000000018");
        ids.put("krzysztof", "00000000-0000-4000-a000-000000000019");
        ids.put("tara", "00000000-0000-4000-a000-000000000020");
        return ids;
    }

    @Test
    @DisplayName("Each sample account is created with its written-out id")
    void eachSampleAccountIsCreatedWithItsWrittenOutId() {
        AccountPort accounts = mock(AccountPort.class);
        when(accounts.findByHandle(any())).thenReturn(Optional.empty());
        when(accounts.create(any())).thenAnswer(call -> call.getArgument(0));

        new LocalAccountSeeder(accounts).run();

        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(20)).create(created.capture());
        Map<String, String> idsByHandle = new LinkedHashMap<>();
        created.getAllValues()
                .forEach(a -> idsByHandle.put(a.getHandle(), a.getId().toString()));
        assertThat(idsByHandle).isEqualTo(EXPECTED_IDS);
    }

    @Test
    @DisplayName("A handle already seeded is skipped")
    void aHandleAlreadySeededIsSkipped() {
        AccountPort accounts = mock(AccountPort.class);
        when(accounts.findByHandle(any())).thenReturn(Optional.empty());
        when(accounts.findByHandle("marco"))
                .thenReturn(
                        Optional.of(new Account(UUID.randomUUID(), "marco", "Marco", "EUR", java.math.BigDecimal.ONE)));
        when(accounts.create(any())).thenAnswer(call -> call.getArgument(0));

        new LocalAccountSeeder(accounts).run();

        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(19)).create(created.capture());
        assertThat(created.getAllValues()).extracting(Account::getHandle).doesNotContain("marco");
        verify(accounts, never())
                .create(org.mockito.ArgumentMatchers.argThat(a -> a.getHandle().equals("marco")));
    }
}
