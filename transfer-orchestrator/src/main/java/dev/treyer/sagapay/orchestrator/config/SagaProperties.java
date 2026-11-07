package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.application.service.SagaSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound at startup, so that a wrong value stops the service instead of stalling its sagas. */
@Validated
@ConfigurationProperties(prefix = "saga")
public record SagaProperties(
        @NotNull @Valid Reprise reprise,
        @NotNull @Valid Deadline deadline,
        @NotNull @Valid Screening screening,
        @NotNull @Valid Confirmation confirmation) {

    public record Reprise(
            @PositiveOrZero long gracePeriodMs,
            @Positive long sweepIntervalMs,
            @Min(1) int batchSize) {}

    public record Deadline(
            @Positive long sweepIntervalMs, @Min(1) int batchSize) {}

    public record Screening(@Positive long timeoutMs) {}

    public record Confirmation(
            @Positive long windowMs, @PositiveOrZero long reservationMarginMs) {}

    public SagaSettings toSettings() {
        return new SagaSettings(
                reprise.gracePeriodMs(),
                screening.timeoutMs(),
                confirmation.windowMs(),
                confirmation.reservationMarginMs(),
                deadline.batchSize(),
                reprise.batchSize());
    }
}
