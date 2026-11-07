package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class OutboxRepositoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private TestEntityManager entityManager;

    private OutboxRow save(UUID aggregateId, String eventType) {
        return saveKeyed(aggregateId, aggregateId.toString(), eventType, Instant.now());
    }

    private OutboxRow saveKeyed(UUID aggregateId, String key, String eventType, Instant createdAt) {
        return outbox.saveAndFlush(new OutboxRow(
                UUID.randomUUID(),
                "Transfer",
                aggregateId,
                key,
                eventType,
                JSON.writeValueAsString(java.util.Map.of("transferId", aggregateId.toString())),
                null,
                createdAt));
    }

    @Test
    void saveAndFindByIdRoundTripsJsonbPayloadAndDefaultsHeadersToEmptyObject() {
        UUID aggregateId = UUID.randomUUID();
        OutboxRow saved = save(aggregateId, "TransferInitiated");
        entityManager.clear();

        OutboxRow found = outbox.findById(saved.getId()).orElseThrow();

        assertThat(JSON.readTree(found.getPayload()).get("transferId").asString())
                .isEqualTo(aggregateId.toString());
        assertThat(JSON.readTree(found.getHeaders())).isEqualTo(JSON.readTree("{}"));
        assertThat(found.getPublishedAt()).isNull();
    }

    @Test
    void findByAggregateIdOrderByCreatedAtReturnsAllEventsForATransferInOrder() {
        UUID aggregateId = UUID.randomUUID();
        save(aggregateId, "TransferInitiated");
        save(aggregateId, "FundsReserved");
        save(UUID.randomUUID(), "TransferInitiated"); // another aggregate

        List<OutboxRow> rows = outbox.findByAggregateIdOrderByCreatedAt(aggregateId);

        assertThat(rows).extracting(OutboxRow::getEventType).containsExactly("TransferInitiated", "FundsReserved");
    }

    @Test
    void findPublishableReturnsTheOldestUnpublishedRowsUpToTheLimit() {
        Instant now = Instant.now();
        OutboxRow published = saveKeyed(UUID.randomUUID(), "a", "TransferInitiated", now.minusSeconds(30));
        OutboxRow older = saveKeyed(UUID.randomUUID(), "b", "TransferInitiated", now.minusSeconds(20));
        OutboxRow newer = saveKeyed(UUID.randomUUID(), "c", "TransferInitiated", now.minusSeconds(10));
        saveKeyed(UUID.randomUUID(), "d", "TransferInitiated", now);
        outbox.markPublished(published.getId());

        List<OutboxRow> found = outbox.findPublishable(2);

        assertThat(found).extracting(OutboxRow::getId).containsExactly(older.getId(), newer.getId());
    }

    @Test
    void aQuarantinedKeyLeavesThePublishableRowsTheAgeAndCountsOnce() {
        Instant now = Instant.now();
        OutboxRow stuck = saveKeyed(UUID.randomUUID(), "stuck", "TransferInitiated", now.minusSeconds(3600));
        saveKeyed(UUID.randomUUID(), "stuck", "TransferInitiated", now.minusSeconds(3500));
        OutboxRow healthy = saveKeyed(UUID.randomUUID(), "healthy", "TransferInitiated", now.minusSeconds(100));

        outbox.quarantine("stuck", stuck.getId(), "too large");
        outbox.quarantine("stuck", stuck.getId(), "too large");

        assertThat(outbox.findPublishable(100)).extracting(OutboxRow::getId).containsExactly(healthy.getId());
        assertThat(outbox.countQuarantinedKeys()).isEqualTo(1);
        assertThat(outbox.oldestPublishableAgeSeconds()).isBetween(100.0, 1000.0);
    }

    @Test
    void theAgeOfAnEmptyOutboxIsZero() {
        assertThat(outbox.oldestPublishableAgeSeconds()).isZero();
    }

    @Test
    void aRowWithoutAKeyFailsItsOwnTransaction() {
        assertThatThrownBy(() -> saveKeyed(UUID.randomUUID(), "", "TransferInitiated", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thePublisherLockIsGrantedToTheCallerHoldingIt() {
        assertThat(outbox.tryAdvisoryTransactionLock(1)).isTrue();
    }

    @Test
    void markPublishedIsIdempotentReturningZeroOnASecondCall() {
        OutboxRow row = save(UUID.randomUUID(), "TransferInitiated");

        int first = outbox.markPublished(row.getId());
        int second = outbox.markPublished(row.getId());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }
}
