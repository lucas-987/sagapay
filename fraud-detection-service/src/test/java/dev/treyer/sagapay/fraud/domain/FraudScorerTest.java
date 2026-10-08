package dev.treyer.sagapay.fraud.domain;

import dev.treyer.sagapay.common.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScorerTest {

    private static final UUID KNOWN = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private final FraudScorer scorer = new FraudScorer(TestSettings.defaults());

    private FraudAssessment assess(UUID recipient, String amount, int inLastHour) {
        return scorer.assess(
                new TransferToScreen(recipient, Money.of(amount, "EUR")),
                new SenderActivity(inLastHour, Set.of(KNOWN)));
    }

    @Test
    void aTransferThatTriggersNoRuleIsClearedWithAZeroScore() {
        FraudAssessment result = assess(KNOWN, "20.00", 1);

        assertThat(result.decision()).isEqualTo(FraudDecision.CLEARED);
        assertThat(result.score()).isEqualByComparingTo("0");
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void velocityAloneClears() {
        FraudAssessment result = assess(KNOWN, "20.00", 6);

        assertThat(result.decision()).isEqualTo(FraudDecision.CLEARED);
        assertThat(result.score()).isEqualByComparingTo("0.40");
        assertThat(result.reasons()).containsExactly(FraudRule.VELOCITY_1H);
    }

    @Test
    void newRecipientAloneClears() {
        FraudAssessment result = assess(STRANGER, "20.00", 1);

        assertThat(result.decision()).isEqualTo(FraudDecision.CLEARED);
        assertThat(result.score()).isEqualByComparingTo("0.35");
        assertThat(result.reasons()).containsExactly(FraudRule.NEW_RECIPIENT);
    }

    @Test
    void amountRoundAloneClears() {
        FraudAssessment result = assess(KNOWN, "600.00", 1);

        assertThat(result.decision()).isEqualTo(FraudDecision.CLEARED);
        assertThat(result.score()).isEqualByComparingTo("0.35");
        assertThat(result.reasons()).containsExactly(FraudRule.AMOUNT_ROUND);
    }

    @Test
    void newRecipientAndRoundAmountReachTheThresholdExactlyAndFlag() {
        FraudAssessment result = assess(STRANGER, "500.00", 1);

        assertThat(result.decision()).isEqualTo(FraudDecision.FLAGGED);
        assertThat(result.score()).isEqualByComparingTo("0.70");
        assertThat(result.reasons()).containsExactly(FraudRule.NEW_RECIPIENT, FraudRule.AMOUNT_ROUND);
    }

    @Test
    void velocityAndNewRecipientFlag() {
        FraudAssessment result = assess(STRANGER, "20.00", 6);

        assertThat(result.decision()).isEqualTo(FraudDecision.FLAGGED);
        assertThat(result.score()).isEqualByComparingTo("0.75");
        assertThat(result.reasons()).containsExactly(FraudRule.VELOCITY_1H, FraudRule.NEW_RECIPIENT);
    }

    @Test
    void allThreeRulesCapTheScoreAtOneAndFlag() {
        FraudAssessment result = assess(STRANGER, "500.00", 6);

        assertThat(result.decision()).isEqualTo(FraudDecision.FLAGGED);
        assertThat(result.score()).isEqualByComparingTo("1.00");
        assertThat(result.reasons())
                .containsExactly(FraudRule.VELOCITY_1H, FraudRule.NEW_RECIPIENT, FraudRule.AMOUNT_ROUND);
    }

    @Test
    void theAssessmentCarriesTheModelVersion() {
        assertThat(assess(KNOWN, "20.00", 0).modelVersion()).isEqualTo("rules-v1");
    }
}
