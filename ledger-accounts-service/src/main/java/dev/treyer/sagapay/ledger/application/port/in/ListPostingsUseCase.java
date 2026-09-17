package dev.treyer.sagapay.ledger.application.port.in;

import dev.treyer.sagapay.ledger.domain.PostingCursor;
import dev.treyer.sagapay.ledger.domain.PostingPage;

import java.time.Instant;
import java.util.UUID;

public interface ListPostingsUseCase {
    PostingPage listPostings(UUID accountId, Instant from, PostingCursor after, int limit);
}
