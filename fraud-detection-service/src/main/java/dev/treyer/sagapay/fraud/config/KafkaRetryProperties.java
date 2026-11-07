package dev.treyer.sagapay.fraud.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Retries of a failed transfer event before the dead-letter topic. Together they must stay well
 * under the orchestrator's screening timeout: a verdict that comes later finds the transfer failed. */
@Validated
@ConfigurationProperties(prefix = "kafka.retry")
public record KafkaRetryProperties(
        @PositiveOrZero long attempts, @PositiveOrZero long intervalMs) {}
