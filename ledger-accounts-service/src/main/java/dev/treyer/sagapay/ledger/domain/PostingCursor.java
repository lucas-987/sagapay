package dev.treyer.sagapay.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** A position rather than an offset: concurrent inserts shift offsets, so pages
 * would skip or repeat rows. {@code id} breaks ties on {@code createdAt}. */
public record PostingCursor(Instant createdAt, UUID id) {

    public static PostingCursor of(Posting posting) {
        return new PostingCursor(posting.getCreatedAt(), posting.getId());
    }
}
