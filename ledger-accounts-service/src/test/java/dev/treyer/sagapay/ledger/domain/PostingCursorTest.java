package dev.treyer.sagapay.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostingCursorTest {

    @Test
    void ofExtractsCreatedAtAndIdFromPosting() {
        Posting posting = new Posting(
                UUID.randomUUID(), UUID.randomUUID(), "transfer-1", PostingLeg.DEBIT, new BigDecimal("1.0000"));

        PostingCursor cursor = PostingCursor.of(posting);

        assertThat(cursor.createdAt()).isEqualTo(posting.getCreatedAt());
        assertThat(cursor.id()).isEqualTo(posting.getId());
    }
}
