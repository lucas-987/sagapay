package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The deadline is set on the gRPC call rather than with Resilience4j's
 * {@code @TimeLimiter}, which only wraps methods returning {@code CompletionStage}.
 * A definitive refusal from the ledger becomes {@link LedgerRejectedException}
 * (the saga fails the transfer); anything else is {@link
 * LedgerUnavailableException} (the reprise poller retries).
 */
@Component
public class LedgerGrpcClientAdapter implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(LedgerGrpcClientAdapter.class);

    /** Codes the ledger uses when the request itself is at fault. */
    private static final Set<Status.Code> REJECTION_CODES =
            Set.of(Status.Code.NOT_FOUND, Status.Code.INVALID_ARGUMENT, Status.Code.ALREADY_EXISTS);

    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub;
    private final long deadlineMs;

    public LedgerGrpcClientAdapter(Channel ledgerChannel, @Value("${ledger.grpc.deadline-ms:500}") long deadlineMs) {
        this.stub = LedgerServiceGrpc.newBlockingStub(ledgerChannel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "checkAndReserveFallback")
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        CheckAndReserveResponse response;
        try {
            response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .checkAndReserve(CheckAndReserveRequest.newBuilder()
                            .setTransferId(transferId)
                            .setFromAccountId(fromAccountId.toString())
                            .setAmount(toProto(amount))
                            .build());
        } catch (StatusRuntimeException e) {
            throw classify(e);
        }

        return switch (response.getStatus()) {
            case OK -> new ReservationResult.Ok(UUID.fromString(response.getReservationId()));
            case INSUFFICIENT_FUNDS -> new ReservationResult.InsufficientFunds();
            default -> throw new IllegalStateException("unexpected CheckAndReserve status: " + response.getStatus());
        };
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
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        PostTransferResponse response;
        try {
            response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .postTransfer(PostTransferRequest.newBuilder()
                            .setTransferId(transferId)
                            .setFromAccountId(fromAccountId.toString())
                            .setToAccountId(toAccountId.toString())
                            .setAmount(toProto(amount))
                            .build());
        } catch (StatusRuntimeException e) {
            throw classify(e);
        }
        return response.getPosted();
    }

    @SuppressWarnings("unused")
    private boolean postTransferFallback(
            String transferId, UUID fromAccountId, UUID toAccountId, Money amount, Throwable t) {
        throw toFallbackException(t);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "releaseReservationFallback")
    public void releaseReservation(String transferId, UUID reservationId) {
        try {
            stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .releaseReservation(ReleaseReservationRequest.newBuilder()
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

    private static RuntimeException classify(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        if (REJECTION_CODES.contains(code)) {
            return new LedgerRejectedException(code.name(), e.getStatus().getDescription(), e);
        }
        return e;
    }

    private static RuntimeException toFallbackException(Throwable t) {
        if (t instanceof LedgerRejectedException rejected) {
            return rejected;
        }
        if (!(t instanceof CallNotPermittedException)) {
            log.warn("Ledger call failed, reported as unavailable", t);
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
