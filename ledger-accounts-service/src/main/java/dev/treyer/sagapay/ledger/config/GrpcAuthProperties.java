package dev.treyer.sagapay.ledger.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** What the ledger's gRPC API requires of a token beyond being the realm's: who it is issued
 * for and what it allows, which a user's token for the REST API does not carry. */
@Validated
@ConfigurationProperties(prefix = "ledger.grpc.auth")
public record GrpcAuthProperties(
        @NotBlank String audience, @NotBlank String scope) {}
