package dev.treyer.sagapay.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code timeoutMs} ends a stream whatever happens on it: the client reconnects and reads its state
 * again over REST. {@code bufferSize} is the number of events one connection may hold unwritten
 * before it is disconnected as too slow. */
@Validated
@ConfigurationProperties(prefix = "activity.stream")
public record ActivityStreamProperties(
        @Positive long timeoutMs, @Min(1) int bufferSize) {}
