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
 * Spring Security locks REST down by default but not gRPC: its gRPC resource-server
 * auto-configuration only activates once a {@code GrpcSecurity} bean exists, so every
 * RPC would be open. Replace with the {@code GrpcSecurity} DSL once tokens can be
 * validated.
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
