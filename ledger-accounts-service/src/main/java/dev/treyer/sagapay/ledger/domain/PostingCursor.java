package dev.treyer.sagapay.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The last page item's {@code (createdAt, id)}, not a plain rank/offset: on an
 * append-only table under continuous writes, an {@code OFFSET} shifts with every
 * concurrent insert between two calls (a page can skip or repeat rows); comparing
 * against a real position stays stable no matter what's been inserted since. {@code
 * id} breaks ties on {@code createdAt} (several postings can share the same instant).
 */
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
            // Catches Base64/UUID/date-parse/missing-separator failures alike: a
            // malformed cursor is never an error a client can tell apart in detail.
            throw new InvalidCursorException("malformed cursor", e);
        }
    }
}
