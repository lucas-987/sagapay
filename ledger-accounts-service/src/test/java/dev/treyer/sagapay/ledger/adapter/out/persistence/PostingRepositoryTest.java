package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.domain.Posting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Postings are inserted through SQL: the tests need chosen, sometimes identical,
 * timestamps, which the entity constructor does not allow. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class PostingRepositoryTest {

    @Autowired
    private PostingRepository postings;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID newAccount() {
        // Flushed so the raw insert below satisfies the foreign key.
        Account account = accounts.saveAndFlush(new Account(
                "test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("0.0000")));
        return account.getId();
    }

    private UUID insertPosting(UUID accountId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into postings (id, entry_group, account_id, transfer_id, leg, amount, created_at) "
                        + "values (?, ?, ?, ?, 'DEBIT', 1.00, ?)",
                id, UUID.randomUUID(), accountId, "transfer-" + id, Timestamp.from(createdAt));
        return id;
    }

    @Test
    void findPageWithNoFiltersDoesNotThrow_regressionForTypeInferenceBug() {
        UUID accountId = newAccount();
        insertPosting(accountId, Instant.now());

        // Both from and the cursor null: Postgres needs the casts to type them.
        List<Posting> page = postings.findPage(accountId, null, null, null, PageRequest.of(0, 10));

        assertThat(page).hasSize(1);
    }

    @Test
    void findPageOrdersByCreatedAtAscending() {
        UUID accountId = newAccount();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID p1 = insertPosting(accountId, base);
        UUID p2 = insertPosting(accountId, base.plusSeconds(10));
        UUID p3 = insertPosting(accountId, base.plusSeconds(20));

        List<Posting> page = postings.findPage(accountId, null, null, null, PageRequest.of(0, 10));

        assertThat(page).extracting(Posting::getId).containsExactly(p1, p2, p3);
    }

    @Test
    void findPageFromFilterExcludesEarlierPostings() {
        UUID accountId = newAccount();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        insertPosting(accountId, base);
        UUID p2 = insertPosting(accountId, base.plusSeconds(10));
        UUID p3 = insertPosting(accountId, base.plusSeconds(20));

        List<Posting> page = postings.findPage(accountId, base.plusSeconds(5), null, null, PageRequest.of(0, 10));

        assertThat(page).extracting(Posting::getId).containsExactly(p2, p3);
    }

    @Test
    void findPageKeysetPaginationCoversAllRowsExactlyOnceAcrossMultiplePages() {
        UUID accountId = newAccount();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID p1 = insertPosting(accountId, base);
        UUID p2 = insertPosting(accountId, base.plusSeconds(1));
        UUID p3 = insertPosting(accountId, base.plusSeconds(2));

        List<Posting> firstPage = postings.findPage(accountId, null, null, null, PageRequest.of(0, 2));
        assertThat(firstPage).extracting(Posting::getId).containsExactly(p1, p2);

        Posting last = firstPage.get(firstPage.size() - 1);
        List<Posting> secondPage = postings.findPage(
                accountId, null, last.getCreatedAt(), last.getId(), PageRequest.of(0, 2));

        assertThat(secondPage).extracting(Posting::getId).containsExactly(p3);
    }

    @Test
    void findPageBreaksTiesById_whenTwoPostingsShareTheExactSameCreatedAt() {
        UUID accountId = newAccount();
        // Debit and credit share an instant. Whatever order the ids give, each row
        // must appear exactly once across the two pages.
        Instant sameInstant = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID pA = insertPosting(accountId, sameInstant);
        UUID pB = insertPosting(accountId, sameInstant);

        List<Posting> firstPage = postings.findPage(accountId, null, null, null, PageRequest.of(0, 1));
        assertThat(firstPage).hasSize(1);
        Posting first = firstPage.get(0);

        List<Posting> secondPage = postings.findPage(
                accountId, null, first.getCreatedAt(), first.getId(), PageRequest.of(0, 1));

        assertThat(secondPage).hasSize(1);
        assertThat(List.of(first.getId(), secondPage.get(0).getId())).containsExactlyInAnyOrder(pA, pB);
    }
}
