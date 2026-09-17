package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostingPort {

    /** Append-only — the only write. */
    void save(Posting posting);

    /**
     * Keyset page ordered by ascending {@code (createdAt, id)}, strictly after
     * {@code after} ({@code null} = from the start) — see {@link PostingCursor} for
     * why there's no offset. {@code maxRows} is a raw row count; it's up to the
     * caller to request {@code limit + 1} if it wants to detect a next page.
     */
    List<Posting> findPage(UUID accountId, Instant from, PostingCursor after, int maxRows);
}
