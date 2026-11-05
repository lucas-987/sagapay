package dev.treyer.sagapay.orchestrator.application.service;

/** The timings of the saga and the size of a deadline sweep. */
public record SagaSettings(
        long repriseGracePeriodMs, long screeningTimeoutMs, long confirmationWindowMs, int overdueBatchSize) {}
