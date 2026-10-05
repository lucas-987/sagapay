package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class SagaStepRepositoryTest {

    @Autowired
    private SagaStepRepository sagaSteps;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    // Satisfies the foreign key with a minimal row.
    private UUID newTransfer() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new java.math.BigDecimal("10.0000"),
                "EUR");
        return id;
    }

    @Test
    void saveAndFindByIdRoundTripsFieldsIncludingNullableJsonbDetail() {
        UUID transferId = newTransfer();
        SagaStep saved = sagaSteps.saveAndFlush(new SagaStep(transferId, "RESERVE", "OK", null));
        entityManager.clear();

        SagaStep found = sagaSteps.findById(saved.getId()).orElseThrow();

        assertThat(found.getTransferId()).isEqualTo(transferId);
        assertThat(found.getStep()).isEqualTo("RESERVE");
        assertThat(found.getOutcome()).isEqualTo("OK");
        assertThat(found.getDetail()).isNull();
    }

    @Test
    void findByTransferIdOrderByAtAscIdAscReturnsStepsInWriteOrder() {
        UUID transferId = newTransfer();
        sagaSteps.saveAndFlush(new SagaStep(transferId, "RESERVE", "OK", null));
        sagaSteps.saveAndFlush(new SagaStep(transferId, "POST", "OK", null));

        List<SagaStep> steps = sagaSteps.findByTransferIdOrderByAtAscIdAsc(transferId);

        assertThat(steps).extracting(SagaStep::getStep).containsExactly("RESERVE", "POST");
    }

    @Test
    void deadlineRoundTripsAndIsAbsentByDefault() {
        UUID transferId = newTransfer();
        Instant deadline = Instant.parse("2026-01-01T10:15:30.123456Z");
        SagaStep withDeadline = sagaSteps.saveAndFlush(new SagaStep(transferId, "SCREEN", "RETRY", null, deadline));
        SagaStep without = sagaSteps.saveAndFlush(new SagaStep(transferId, "POST", "OK", null));
        entityManager.clear();

        assertThat(sagaSteps.findById(withDeadline.getId()).orElseThrow().getDeadline())
                .isEqualTo(deadline);
        assertThat(sagaSteps.findById(without.getId()).orElseThrow().getDeadline())
                .isNull();
    }
}
