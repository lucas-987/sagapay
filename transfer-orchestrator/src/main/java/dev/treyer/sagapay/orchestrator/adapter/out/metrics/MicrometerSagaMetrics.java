package dev.treyer.sagapay.orchestrator.adapter.out.metrics;

import dev.treyer.sagapay.orchestrator.application.port.out.SagaMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerSagaMetrics implements SagaMetricsPort {

    private final Counter confirmationWindowShortened;

    MicrometerSagaMetrics(MeterRegistry registry) {
        this.confirmationWindowShortened = registry.counter("saga.confirmation.window.shortened");
    }

    @Override
    public void confirmationWindowShortened() {
        confirmationWindowShortened.increment();
    }
}
