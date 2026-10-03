package dev.treyer.sagapay.ledger.application.port.out;

import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostingPort {

    void save(Posting posting);

    /** Keyset page ordered by {@code (createdAt, id)}, strictly after {@code after}
     * ({@code null} for the first page). */
    List<Posting> findPage(UUID accountId, Instant from, PostingCursor after, int maxRows);
}
