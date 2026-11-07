package dev.treyer.sagapay.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(@NotNull @Valid Grpc grpc) {

    public record Grpc(@Positive long deadlineMs) {}
}
