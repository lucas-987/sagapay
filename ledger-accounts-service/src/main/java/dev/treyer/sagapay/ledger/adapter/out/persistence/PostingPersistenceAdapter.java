package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.application.port.out.PostingPort;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class PostingPersistenceAdapter implements PostingPort {

    private final PostingRepository repository;

    PostingPersistenceAdapter(PostingRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Posting posting) {
        repository.save(posting);
    }

    @Override
    public List<Posting> findPage(UUID accountId, Instant from, PostingCursor after, int maxRows) {
        Instant afterCreatedAt = after == null ? null : after.createdAt();
        UUID afterId = after == null ? null : after.id();
        return repository.findPage(accountId, from, afterCreatedAt, afterId, PageRequest.of(0, maxRows));
    }
}
