package dev.treyer.sagapay.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** A position rather than an offset: concurrent inserts shift offsets, so pages
 * would skip or repeat rows. {@code id} breaks ties on {@code createdAt}. */
public record PostingCursor(Instant createdAt, UUID id) {

    private static final String SEPARATOR = "|";

    public static PostingCursor of(Posting posting) {
        return new PostingCursor(posting.getCreatedAt(), posting.getId());
    }

    public String encode() {
        String raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PostingCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEPARATOR);
            return new PostingCursor(Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("malformed cursor", e);
        }
    }
}
