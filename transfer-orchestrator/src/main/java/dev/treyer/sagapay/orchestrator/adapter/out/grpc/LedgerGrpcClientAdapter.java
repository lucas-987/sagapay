package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import com.google.protobuf.Timestamp;
import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.config.LedgerProperties;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The deadline is set on the gRPC call rather than with Resilience4j's
 * {@code @TimeLimiter}, which only wraps methods returning {@code CompletionStage}.
 * A definitive refusal from the ledger becomes {@link LedgerRejectedException}
 * (the saga fails the transfer); anything else is {@link
 * LedgerUnavailableException} (the reprise poller retries). That includes every credential
 * problem: the ledger is not wrong about the request, the orchestrator has no accepted token yet.
 */
@Component
public class LedgerGrpcClientAdapter implements LedgerPort {

    /** Codes the ledger uses when the request itself is at fault. */
    private static final Set<Status.Code> REJECTION_CODES =
            Set.of(Status.Code.NOT_FOUND, Status.Code.INVALID_ARGUMENT, Status.Code.ALREADY_EXISTS);

    private static final Logger log = LoggerFactory.getLogger(LedgerGrpcClientAdapter.class);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub;
    private final long deadlineMs;
    private final ServiceTokenProvider tokens;

    public LedgerGrpcClientAdapter(Channel ledgerChannel, LedgerProperties properties, ServiceTokenProvider tokens) {
        this.stub = LedgerServiceGrpc.newBlockingStub(ledgerChannel);
        this.deadlineMs = properties.grpc().deadlineMs();
        this.tokens = tokens;
    }

    /**
     * Always the service's own token, never the caller's: the ledger's API is for services. The
     * token is fetched before the deadline starts, which then covers the call alone.
     */
    private LedgerServiceGrpc.LedgerServiceBlockingStub authenticated() {
        String token;
        try {
            token = tokens.token();
        } catch (ServiceTokenUnavailableException e) {
            throw new LedgerUnavailableException(e);
        }
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "checkAndReserveFallback")
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        LedgerServiceGrpc.LedgerServiceBlockingStub call = authenticated();
        CheckAndReserveResponse response;
        try {
            response = call.checkAndReserve(CheckAndReserveRequest.newBuilder()
                    .setTransferId(transferId)
                    .setFromAccountId(fromAccountId.toString())
                    .setAmount(toProto(amount))
                    .build());
        } catch (StatusRuntimeException e) {
            throw classify(e);
        }

        return switch (response.getStatus()) {
            case OK -> new ReservationResult.Ok(UUID.fromString(response.getReservationId()), expiresAt(response));
            case INSUFFICIENT_FUNDS -> new ReservationResult.InsufficientFunds();
            default -> throw new IllegalStateException("unexpected CheckAndReserve status: " + response.getStatus());
        };
    }

    /** The confirmation deadline depends on this date, so an answer without it is not guessed around. */
    private static Instant expiresAt(CheckAndReserveResponse response) {
        if (!response.hasExpiresAt()) {
            throw new IllegalStateException(
                    "reservation of transfer " + response.getReservationId() + " came without its expiry");
        }
        Timestamp expiresAt = response.getExpiresAt();
        return Instant.ofEpochSecond(expiresAt.getSeconds(), expiresAt.getNanos());
    }

    /** Found by Resilience4j through its signature. It also receives the
     * exceptions the breaker ignores, hence the rejection passthrough. */
    @SuppressWarnings("unused")
    private ReservationResult checkAndReserveFallback(
            String transferId, UUID fromAccountId, Money amount, Throwable t) {
        throw toFallbackException(t);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "postTransferFallback")
    public void postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        LedgerServiceGrpc.LedgerServiceBlockingStub call = authenticated();
        try {
            call.postTransfer(PostTransferRequest.newBuilder()
                    .setTransferId(transferId)
                    .setFromAccountId(fromAccountId.toString())
                    .setToAccountId(toAccountId.toString())
                    .setAmount(toProto(amount))
                    .build());
        } catch (StatusRuntimeException e) {
            throw classify(e);
        }
    }

    @SuppressWarnings("unused")
    private void postTransferFallback(
            String transferId, UUID fromAccountId, UUID toAccountId, Money amount, Throwable t) {
        throw toFallbackException(t);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "releaseReservationFallback")
    public void releaseReservation(String transferId, UUID reservationId) {
        LedgerServiceGrpc.LedgerServiceBlockingStub call = authenticated();
        try {
            call.releaseReservation(ReleaseReservationRequest.newBuilder()
                    .setTransferId(transferId)
                    .setReservationId(reservationId.toString())
                    .build());
        } catch (StatusRuntimeException e) {
            throw classify(e);
        }
    }

    @SuppressWarnings("unused")
    private void releaseReservationFallback(String transferId, UUID reservationId, Throwable t) {
        throw toFallbackException(t);
    }

    private RuntimeException classify(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        if (REJECTION_CODES.contains(code)) {
            return new LedgerRejectedException(code.name(), e.getStatus().getDescription(), e);
        }
        if (code == Status.Code.UNAUTHENTICATED) {
            // Another attempt with the same token would be refused the same way.
            tokens.drop();
        }
        if (code == Status.Code.UNAUTHENTICATED || code == Status.Code.PERMISSION_DENIED) {
            log.warn("ledger refused the service token", kv("grpc.status", code.name()));
        }
        return new LedgerUnavailableException(e);
    }

    private static RuntimeException toFallbackException(Throwable t) {
        if (t instanceof LedgerRejectedException || t instanceof LedgerUnavailableException) {
            return (RuntimeException) t;
        }
        return new LedgerUnavailableException(t);
    }

    private static dev.treyer.sagapay.common.v1.Money toProto(Money money) {
        return dev.treyer.sagapay.common.v1.Money.newBuilder()
                .setCurrency(money.currency().getCurrencyCode())
                .setAmount(money.toPlainString())
                .build();
    }
}
