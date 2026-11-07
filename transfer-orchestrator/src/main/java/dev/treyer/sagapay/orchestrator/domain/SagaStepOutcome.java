package dev.treyer.sagapay.orchestrator.domain;

/** RETRY: the step is still open, waiting for a verdict, a confirmation or a release. */
public enum SagaStepOutcome {
    OK,
    FAILED,
    RETRY,
    COMPENSATED
}
