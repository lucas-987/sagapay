package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.domain.FailureReason;
import dev.treyer.sagapay.orchestrator.domain.SagaStepName;
import dev.treyer.sagapay.orchestrator.domain.SagaStepOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every enum value is written here: a value added without its migration would otherwise only
 * fail at runtime. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class SagaIntegrityConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID newTransfer() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into transfers (id, idempotency_key, sender_id, sender_account_id,
                    recipient_id, recipient_account_id, amount, currency)
                values (?, ?, ?, ?, ?, ?, 25, 'EUR')
                """, id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private void insertStep(String step, String outcome) {
        jdbc.update(
                "insert into saga_steps (transfer_id, step, outcome) values (?, ?, ?)", newTransfer(), step, outcome);
    }

    private void setFailureReason(String reason) {
        jdbc.update("update transfers set failure_reason = ? where id = ?", reason, newTransfer());
    }

    @ParameterizedTest
    @EnumSource(SagaStepName.class)
    void everyStepNameIsAccepted(SagaStepName step) {
        assertThatCode(() -> insertStep(step.name(), "OK")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(SagaStepOutcome.class)
    void everyOutcomeIsAccepted(SagaStepOutcome outcome) {
        assertThatCode(() -> insertStep("POST", outcome.name())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(FailureReason.class)
    void everyFailureReasonIsAccepted(FailureReason reason) {
        assertThatCode(() -> setFailureReason(reason.name())).doesNotThrowAnyException();
    }

    @Test
    void anUnknownStepIsRefused() {
        assertThatThrownBy(() -> insertStep("REFUND", "OK")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownOutcomeIsRefused() {
        assertThatThrownBy(() -> insertStep("POST", "MAYBE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownFailureReasonIsRefused() {
        assertThatThrownBy(() -> setFailureReason("BECAUSE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.0001", "1.0001"})
    void aFraudScoreOutOfRangeIsRefused(String score) {
        assertThatThrownBy(() -> jdbc.update(
                        "update transfers set fraud_score = ? where id = ?", new BigDecimal(score), newTransfer()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    void theBoundsOfTheFraudScoreAreAccepted(String score) {
        assertThatCode(() -> jdbc.update(
                        "update transfers set fraud_score = ? where id = ?", new BigDecimal(score), newTransfer()))
                .doesNotThrowAnyException();
    }
}
