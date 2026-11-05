package dev.treyer.sagapay.fraud.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** A larger batch holds its row locks longer and republishes more rows after a rollback: every
 * send is awaited inside the transaction. */
@Validated
@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(
        @Positive long pollIntervalMs, @Min(1) int batchSize) {}
