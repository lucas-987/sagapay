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

/** Regression coverage for a Postgres type-inference bug in {@link
 * PostingRepository#findPage} ({@code PSQLException: could not determine data type
 * of parameter}, see ADR 0004). Postings are inserted directly via SQL ({@code
 * jdbcTemplate}) rather than through {@link Posting}'s constructor, which always
 * sets {@code createdAt = Instant.now()} — these tests need precise, sometimes
 * identical, timestamps for the tie-break-by-id test below. */
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
        // saveAndFlush: the account row must physically exist before the raw JDBC
        // insert below, or the postings_account_id_fkey FK fails against
        // Hibernate's still-unflushed persistence context.
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

        // Before the fix, this call failed with "could not determine data type of
        // parameter $2" whenever both from and the cursor were null, as here.
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
        // postTransfer inserts debit + credit at almost the same instant (same
        // entry_group), which is why id must break the tie. This test makes no
        // assumption about which order Postgres actually picks between the two
        // UUIDs — only that each row comes out exactly once across two consecutive
        // pages, never duplicated or skipped.
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
