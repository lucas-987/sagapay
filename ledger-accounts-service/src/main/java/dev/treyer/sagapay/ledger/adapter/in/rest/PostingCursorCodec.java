package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.ledger.domain.PostingCursor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The {@code cursor} query parameter: an opaque token for the client, a position for the
 * use case. */
final class PostingCursorCodec {

    private static final String SEPARATOR = "|";

    private PostingCursorCodec() {}

    static String encode(PostingCursor cursor) {
        String raw = cursor.createdAt() + SEPARATOR + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static PostingCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEPARATOR);
            return new PostingCursor(Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("malformed cursor", e);
        }
    }
}
