package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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

    private static final long DEADLINE_MS = 500;

    /** Codes the ledger uses when the request itself is at fault. */
    private static final Set<Status.Code> REJECTION_CODES =
            Set.of(Status.Code.NOT_FOUND, Status.Code.INVALID_ARGUMENT, Status.Code.ALREADY_EXISTS);

    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub;

    public LedgerGrpcClientAdapter(Channel ledgerChannel) {
        this.stub = LedgerServiceGrpc.newBlockingStub(ledgerChannel);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "checkAndReserveFallback")
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        CheckAndReserveResponse response;
        try {
            response = stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
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
            response = stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
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
        return new LedgerUnavailableException(t);
    }

    private static dev.treyer.sagapay.common.v1.Money toProto(Money money) {
        return dev.treyer.sagapay.common.v1.Money.newBuilder()
                .setCurrency(money.currency().getCurrencyCode())
                .setAmount(money.toPlainString())
                .build();
    }
}
