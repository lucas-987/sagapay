package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class OutboxRepositoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private OutboxRepository outbox;
    @Autowired
    private TestEntityManager entityManager;

    private OutboxRow save(UUID aggregateId, String eventType) {
        return outbox.saveAndFlush(new OutboxRow(UUID.randomUUID(), "Transfer", aggregateId, eventType,
                JSON.writeValueAsString(java.util.Map.of("transferId", aggregateId.toString())), null));
    }

    @Test
    void saveAndFindByIdRoundTripsJsonbPayloadAndDefaultsHeadersToEmptyObject() {
        UUID aggregateId = UUID.randomUUID();
        OutboxRow saved = save(aggregateId, "TransferInitiated");
        entityManager.clear();

        OutboxRow found = outbox.findById(saved.getId()).orElseThrow();

        assertThat(JSON.readTree(found.getPayload()).get("transferId").asString()).isEqualTo(aggregateId.toString());
        assertThat(JSON.readTree(found.getHeaders())).isEqualTo(JSON.readTree("{}"));
        assertThat(found.getPublishedAt()).isNull();
    }

    @Test
    void findByAggregateIdOrderByCreatedAtReturnsAllEventsForATransferInOrder() {
        UUID aggregateId = UUID.randomUUID();
        save(aggregateId, "TransferInitiated");
        save(aggregateId, "FundsReserved");
        save(UUID.randomUUID(), "TransferInitiated"); // different aggregate, must not leak in

        List<OutboxRow> rows = outbox.findByAggregateIdOrderByCreatedAt(aggregateId);

        assertThat(rows).extracting(OutboxRow::getEventType).containsExactly("TransferInitiated", "FundsReserved");
    }

    @Test
    void findUnpublishedForUpdateSkipLockedOnlyReturnsUnpublishedRowsUpToLimit() {
        OutboxRow a = save(UUID.randomUUID(), "TransferInitiated");
        save(UUID.randomUUID(), "TransferInitiated");
        outbox.markPublished(a.getId());

        List<OutboxRow> unpublished = outbox.findUnpublishedForUpdateSkipLocked(100);

        assertThat(unpublished).extracting(OutboxRow::getId).doesNotContain(a.getId());
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
