package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class AccountRepositoryTest {

    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByHandleFindsExistingAccount() {
        String handle = "test-" + UUID.randomUUID();
        Account account = accounts.saveAndFlush(new Account(handle, "Test User", "EUR", new BigDecimal("10.0000")));

        Optional<Account> found = accounts.findByHandle(handle);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(account.getId());
    }

    @Test
    void findByHandleIsEmptyForUnknownHandle() {
        assertThat(accounts.findByHandle("does-not-exist")).isEmpty();
    }

    @Test
    void debitIfSufficientFundsDebitsWhenBalanceCovers() {
        Account account = accounts.saveAndFlush(new Account(
                "test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("100.0000")));

        int updated = accounts.debitIfSufficientFunds(account.getId(), new BigDecimal("40.0000"));
        // @Modifying bypasses the persistence context (a direct SQL UPDATE) —
        // without this clear(), the findById right after would return the entity
        // cached by saveAndFlush above, with its balance from BEFORE the debit
        // (classic JPA pitfall: the L1 cache doesn't know a bulk update happened).
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(accounts.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("60.0000");
    }

    @Test
    void debitIfSufficientFundsDoesNothingWhenBalanceTooLow() {
        Account account = accounts.saveAndFlush(new Account(
                "test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("10.0000")));

        int updated = accounts.debitIfSufficientFunds(account.getId(), new BigDecimal("40.0000"));
        entityManager.clear();

        assertThat(updated).isEqualTo(0);
        assertThat(accounts.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("10.0000");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = accounts.saveAndFlush(new Account(
                "test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("10.0000")));

        accounts.credit(account.getId(), new BigDecimal("5.0000"));
        entityManager.clear();

        assertThat(accounts.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("15.0000");
    }

    @Test
    void findByIdForUpdateFindsExistingAccount() {
        Account account = accounts.saveAndFlush(new Account(
                "test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("10.0000")));

        // Lock behavior under concurrency is covered separately in LedgerServiceTest —
        // this only checks the query itself returns the right row.
        assertThat(accounts.findByIdForUpdate(account.getId())).isPresent();
    }

    @Test
    void findByIdForUpdateIsEmptyForUnknownAccount() {
        assertThat(accounts.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }
}
