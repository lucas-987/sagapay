package dev.treyer.sagapay.ledger.adapter.in.grpc;

import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * Republishes the auto-configured gRPC observation interceptor as a global one:
 * verified that its auto-configured bean carries only {@code @Bean}/{@code
 * @Order}, never {@code @GlobalServerInterceptor} — without this class, the 4
 * {@code LedgerGrpcAdapter} RPCs would produce no spans, unlike REST which gets
 * them for free.
 *
 * <p>Reuses the auto-configured bean as-is rather than creating a second
 * instance, which would duplicate metrics/spans.
 */
@Configuration
class GrpcTracingInterceptorConfig {

    @Bean
    @GlobalServerInterceptor
    ServerInterceptor grpcObservationGlobalInterceptor(ObservationGrpcServerInterceptor autoConfigured) {
        return autoConfigured;
    }
}
