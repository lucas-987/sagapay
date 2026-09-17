package dev.treyer.sagapay.ledger.adapter.in.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * Locks gRPC down by default — REST gets this for free (spring-boot-starter-
 * security-oauth2-resource-server locks everything down as soon as no {@code
 * SecurityFilterChain} is defined), but gRPC has no such default: verified that
 * {@code GrpcServerOAuth2ResourceServerAutoConfiguration} only activates once a
 * {@code GrpcSecurity} bean already exists, so without this class every RPC is
 * open in every profile. No real JWT validation here — deferred until Keycloak
 * is wired; once it is, replace this with spring-grpc-core's {@code GrpcSecurity}
 * DSL instead of extending this hand-rolled interceptor.
 *
 * <p>{@code @GlobalServerInterceptor} applies it to every RPC automatically —
 * nothing to reference from {@code LedgerGrpcAdapter}.
 */
@Component
@GlobalServerInterceptor
@Profile("!local-noauth")
class GrpcDenyByDefaultInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        call.close(Status.UNAUTHENTICATED.withDescription(
                "gRPC requires the local-noauth profile until real authentication is wired"), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
