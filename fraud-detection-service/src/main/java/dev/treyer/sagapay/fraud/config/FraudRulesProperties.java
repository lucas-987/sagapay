package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.domain.FraudRuleSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/** Bound at startup: a negative weight or a threshold out of the score range would silently
 * change what gets flagged. */
@Validated
@ConfigurationProperties(prefix = "fraud.rules")
public record FraudRulesProperties(
        @NotNull @Valid Velocity velocity,
        @NotNull @Valid NewRecipient newRecipient,
        @NotNull @Valid AmountRound amountRound,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal flagThreshold,
        @NotBlank String modelVersion) {

    public record Velocity(
            @NotNull @DecimalMin("0") BigDecimal weight,
            @PositiveOrZero int maxInWindow,
            @Positive long windowSeconds) {}

    public record NewRecipient(
            @NotNull @DecimalMin("0") BigDecimal weight,
            @Positive long memorySeconds) {}

    public record AmountRound(
            @NotNull @DecimalMin("0") BigDecimal weight,
            @NotNull @PositiveOrZero BigDecimal threshold,
            @NotNull @Positive BigDecimal step) {}

    public FraudRuleSettings toSettings() {
        return new FraudRuleSettings(
                velocity.weight(),
                velocity.maxInWindow(),
                newRecipient.weight(),
                amountRound.weight(),
                amountRound.threshold(),
                amountRound.step(),
                flagThreshold,
                modelVersion);
    }
}
