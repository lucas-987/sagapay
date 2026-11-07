package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Read from the database at each scrape: only the instance holding the publisher lock could keep
 * them up to date in memory, and a stalled outbox is exactly when no instance publishes. */
@Component
class OutboxMetrics {

    OutboxMetrics(MeterRegistry registry, OutboxRelayPort outbox) {
        Gauge.builder("outbox.oldest.unpublished.age", outbox, OutboxRelayPort::oldestPublishableAgeSeconds)
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("outbox.quarantined.keys", outbox, OutboxRelayPort::countQuarantinedKeys)
                .register(registry);
    }
}
