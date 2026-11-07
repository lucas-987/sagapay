package dev.treyer.sagapay.orchestrator.application.service;

/** The timings of the saga and the size of its sweeps. */
public record SagaSettings(
        long repriseGracePeriodMs,
        long screeningTimeoutMs,
        long confirmationWindowMs,
        long reservationMarginMs,
        int overdueBatchSize,
        int repriseBatchSize) {}
