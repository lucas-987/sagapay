package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class FraudScorer {

    private static final BigDecimal MAX_SCORE = BigDecimal.ONE;

    private final FraudRuleSettings settings;

    public FraudScorer(FraudRuleSettings settings) {
        this.settings = settings;
    }

    public FraudAssessment assess(TransferToScreen transfer, SenderActivity activity) {
        List<FraudRule> fired = Arrays.stream(FraudRule.values())
                .filter(rule -> rule.fires(transfer, activity, settings))
                .toList();
        BigDecimal score = fired.stream()
                .map(settings::weightOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(MAX_SCORE);
        FraudDecision decision =
                score.compareTo(settings.flagThreshold()) >= 0 ? FraudDecision.FLAGGED : FraudDecision.CLEARED;
        return new FraudAssessment(score, decision, fired, settings.modelVersion());
    }
}
