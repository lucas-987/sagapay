package dev.treyer.sagapay.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingCursorTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        PostingCursor cursor = new PostingCursor(Instant.parse("2026-01-01T12:00:00.123456Z"), UUID.randomUUID());

        PostingCursor decoded = PostingCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void decodeRejectsMalformedBase64() {
        assertThatThrownBy(() -> PostingCursor.decode("not-valid-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decodeRejectsWellFormedBase64WithoutTheExpectedSeparator() {
        String noSeparator = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nothing-to-see-here".getBytes());

        assertThatThrownBy(() -> PostingCursor.decode(noSeparator))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void ofExtractsCreatedAtAndIdFromPosting() {
        Posting posting = new Posting(
                UUID.randomUUID(), UUID.randomUUID(), "transfer-1", PostingLeg.DEBIT, new BigDecimal("1.0000"));

        PostingCursor cursor = PostingCursor.of(posting);

        assertThat(cursor.createdAt()).isEqualTo(posting.getCreatedAt());
        assertThat(cursor.id()).isEqualTo(posting.getId());
    }
}
