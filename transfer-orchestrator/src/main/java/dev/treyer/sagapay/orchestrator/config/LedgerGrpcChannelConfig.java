package dev.treyer.sagapay.orchestrator.config;

import io.grpc.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Builds the one gRPC {@link Channel} this service needs, named {@code
 * "ledger"} so it picks up {@code spring.grpc.client.channel.ledger.*} —
 * verified via the parent BOM (spring-boot-dependencies:4.1.1) and the
 * spring-grpc-core sources, not assumed: {@link GrpcChannelFactory} is the
 * auto-configured entry point for the client starter, {@code createChannel(name)}
 * resolves that channel's config by the same name. */
@Configuration
class LedgerGrpcChannelConfig {

    @Bean
    Channel ledgerChannel(GrpcChannelFactory channelFactory) {
        return channelFactory.createChannel("ledger");
    }
}
