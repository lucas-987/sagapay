package dev.treyer.sagapay.orchestrator.application.port.out;

public interface SagaMetricsPort {

    /** A reservation expiring too soon cut the window a blocked transfer gets: the sign that the
     * ledger's reservation time does not fit the saga's timings. */
    void confirmationWindowShortened();
}
