package dev.treyer.sagapay.orchestrator.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Retries of a failed fraud result before the dead-letter topic. Together they must stay well
 * under the screening timeout: a result that is still retrying past it finds the transfer failed. */
@Validated
@ConfigurationProperties(prefix = "kafka.retry")
public record KafkaRetryProperties(
        @PositiveOrZero long attempts, @PositiveOrZero long intervalMs) {}
