package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.domain.TransferCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferCursorCodecTest {

    @Test
    void encodeThenDecodeRoundTrips() {
        TransferCursor cursor = new TransferCursor(Instant.parse("2026-01-01T12:00:00.123456Z"), UUID.randomUUID());

        TransferCursor decoded = TransferCursorCodec.decode(TransferCursorCodec.encode(cursor));

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void decodeRejectsMalformedBase64() {
        assertThatThrownBy(() -> TransferCursorCodec.decode("not-valid-base64!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void decodeRejectsWellFormedBase64WithoutTheExpectedSeparator() {
        String noSeparator = Base64.getUrlEncoder().withoutPadding().encodeToString("nothing-to-see-here".getBytes());

        assertThatThrownBy(() -> TransferCursorCodec.decode(noSeparator)).isInstanceOf(InvalidCursorException.class);
    }
}
