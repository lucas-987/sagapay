package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.ledger.domain.PostingCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingCursorCodecTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        PostingCursor cursor = new PostingCursor(Instant.parse("2026-01-01T12:00:00.123456Z"), UUID.randomUUID());

        PostingCursor decoded = PostingCursorCodec.decode(PostingCursorCodec.encode(cursor));

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void decodeRejectsMalformedBase64() {
        assertThatThrownBy(() -> PostingCursorCodec.decode("not-valid-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decodeRejectsWellFormedBase64WithoutTheExpectedSeparator() {
        String noSeparator = Base64.getUrlEncoder().withoutPadding().encodeToString("nothing-to-see-here".getBytes());

        assertThatThrownBy(() -> PostingCursorCodec.decode(noSeparator)).isInstanceOf(InvalidCursorException.class);
    }
}
