package dev.treyer.sagapay.ledger.adapter.in.grpc;

import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * The auto-configured observation interceptor is not registered as a global gRPC
 * interceptor, so RPCs would produce no spans. The existing bean is reused: a second
 * instance would duplicate spans and metrics.
 */
@Configuration
class GrpcTracingInterceptorConfig {

    @Bean
    @GlobalServerInterceptor
    ServerInterceptor grpcObservationGlobalInterceptor(ObservationGrpcServerInterceptor autoConfigured) {
        return autoConfigured;
    }
}
