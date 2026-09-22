package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.Channel;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Adapter out — gRPC client to the ledger. Own {@code Money} <-> proto mapping,
 * not shared with the server-side {@code LedgerGrpcAdapter}: same choice as
 * between REST and gRPC in M1, these are transport details private to each side.
 *
 * <p>Per {@code ledger.proto}: {@code CheckAndReserveRequest} carries no {@code
 * to_account_id} — the recipient only appears at {@code PostTransfer}. Not
 * threaded through earlier "just in case".
 *
 * <p>The 500ms budget is enforced via gRPC's own per-call deadline ({@code
 * withDeadlineAfter}), not Resilience4j's {@code @TimeLimiter}: that annotation's
 * AOP aspect only wraps methods returning {@code CompletionStage}, and this
 * adapter stays synchronous like the rest of the codebase (a fixed deadline
 * doesn't need an async wrapper to be enforced).
 */
@Component
public class LedgerGrpcClientAdapter implements LedgerPort {

    private static final long DEADLINE_MS = 500;

    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub;

    public LedgerGrpcClientAdapter(Channel ledgerChannel) {
        this.stub = LedgerServiceGrpc.newBlockingStub(ledgerChannel);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "checkAndReserveFallback")
    public ReservationResult checkAndReserve(String transferId, UUID fromAccountId, Money amount) {
        CheckAndReserveResponse response = stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(transferId)
                        .setFromAccountId(fromAccountId.toString())
                        .setAmount(toProto(amount))
                        .build());

        return switch (response.getStatus()) {
            case OK -> new ReservationResult.Ok(UUID.fromString(response.getReservationId()));
            case INSUFFICIENT_FUNDS -> new ReservationResult.InsufficientFunds();
            default -> throw new IllegalStateException(
                    "unexpected CheckAndReserve status: " + response.getStatus());
        };
    }

    /** Resolved by Resilience4j via reflection: same parameter list as {@link
     * #checkAndReserve} plus a trailing {@code Throwable}, invoked whenever that
     * method throws (a real failure) or the circuit is already open ({@code
     * CallNotPermittedException}) — the caller can't and needn't tell those
     * apart, see {@link LedgerUnavailableException}. */
    @SuppressWarnings("unused")
    private ReservationResult checkAndReserveFallback(String transferId, UUID fromAccountId, Money amount, Throwable t) {
        throw new LedgerUnavailableException(t);
    }

    @Override
    @CircuitBreaker(name = "ledger", fallbackMethod = "postTransferFallback")
    public boolean postTransfer(String transferId, UUID fromAccountId, UUID toAccountId, Money amount) {
        PostTransferResponse response = stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .postTransfer(PostTransferRequest.newBuilder()
                        .setTransferId(transferId)
                        .setFromAccountId(fromAccountId.toString())
                        .setToAccountId(toAccountId.toString())
                        .setAmount(toProto(amount))
                        .build());
        return response.getPosted();
    }

    @SuppressWarnings("unused")
    private boolean postTransferFallback(String transferId, UUID fromAccountId, UUID toAccountId, Money amount, Throwable t) {
        throw new LedgerUnavailableException(t);
    }

    private static dev.treyer.sagapay.common.v1.Money toProto(Money money) {
        return dev.treyer.sagapay.common.v1.Money.newBuilder()
                .setCurrency(money.currency().getCurrencyCode())
                .setAmount(money.toPlainString())
                .build();
    }
}
