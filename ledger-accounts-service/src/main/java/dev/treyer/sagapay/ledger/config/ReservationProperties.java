package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.application.service.ReservationSettings;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The minimum leaves a reservation time to outlast the fraud screening, whose deadline is not cut
 * short by the date of the reservation. */
@Validated
@ConfigurationProperties(prefix = "ledger.reservation")
public record ReservationProperties(@Min(60_000) long ttlMs) {

    public ReservationSettings toSettings() {
        return new ReservationSettings(ttlMs);
    }
}
