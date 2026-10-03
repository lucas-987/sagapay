package dev.treyer.sagapay.orchestrator.config;

import io.grpc.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** The channel name selects the {@code spring.grpc.client.channel.ledger.*}
 * properties. */
@Configuration
class LedgerGrpcChannelConfig {

    @Bean
    Channel ledgerChannel(GrpcChannelFactory channelFactory) {
        return channelFactory.createChannel("ledger");
    }
}
