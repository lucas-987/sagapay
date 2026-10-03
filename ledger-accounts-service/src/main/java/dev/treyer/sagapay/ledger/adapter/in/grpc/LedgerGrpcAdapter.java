package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.v1.Empty;
import dev.treyer.sagapay.ledger.application.port.in.CheckAndReserveUseCase;
import dev.treyer.sagapay.ledger.application.port.in.GetBalanceUseCase;
import dev.treyer.sagapay.ledger.application.port.in.PostTransferUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ReleaseReservationUseCase;
import dev.treyer.sagapay.ledger.domain.CheckAndReserveResult;
import dev.treyer.sagapay.ledger.domain.MalformedRequestException;
import dev.treyer.sagapay.ledger.v1.*;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

/** Exceptions are left to {@code LedgerGrpcExceptionAdvice}, which maps them to a
 * {@code Status}. */
@GrpcService
class LedgerGrpcAdapter extends LedgerServiceGrpc.LedgerServiceImplBase {

    private final CheckAndReserveUseCase checkAndReserveUseCase;
    private final PostTransferUseCase postTransferUseCase;
    private final ReleaseReservationUseCase releaseReservationUseCase;
    private final GetBalanceUseCase getBalanceUseCase;

    LedgerGrpcAdapter(CheckAndReserveUseCase checkAndReserveUseCase,
                      PostTransferUseCase postTransferUseCase,
                      ReleaseReservationUseCase releaseReservationUseCase,
                      GetBalanceUseCase getBalanceUseCase) {
        this.checkAndReserveUseCase = checkAndReserveUseCase;
        this.postTransferUseCase = postTransferUseCase;
        this.releaseReservationUseCase = releaseReservationUseCase;
        this.getBalanceUseCase = getBalanceUseCase;
    }

    @Override
    public void checkAndReserve(CheckAndReserveRequest request, StreamObserver<CheckAndReserveResponse> responseObserver) {
        var result = checkAndReserveUseCase.checkAndReserve(
                request.getTransferId(),
                parseUuid("fromAccountId", request.getFromAccountId()),
                toMoney(request.getAmount()));

        CheckAndReserveResponse response = switch (result) {
            case CheckAndReserveResult.Ok ok -> CheckAndReserveResponse.newBuilder()
                    .setStatus(CheckAndReserveResponse.Status.OK)
                    .setReservationId(ok.reservationId().toString())
                    .build();
            case CheckAndReserveResult.InsufficientFunds ignored -> CheckAndReserveResponse.newBuilder()
                    .setStatus(CheckAndReserveResponse.Status.INSUFFICIENT_FUNDS)
                    .build();
        };
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void postTransfer(PostTransferRequest request, StreamObserver<PostTransferResponse> responseObserver) {
        var result = postTransferUseCase.postTransfer(
                request.getTransferId(),
                parseUuid("fromAccountId", request.getFromAccountId()),
                parseUuid("toAccountId", request.getToAccountId()),
                toMoney(request.getAmount())
        );

        PostTransferResponse response = PostTransferResponse.newBuilder()
                .setPosted(result)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void releaseReservation(ReleaseReservationRequest request, StreamObserver<Empty> responseObserver) {
        releaseReservationUseCase.releaseReservation(
                request.getTransferId(),
                parseUuid("reservationId", request.getReservationId()));
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
        Money balance = getBalanceUseCase.getBalance(parseUuid("accountId", request.getAccountId()));
        responseObserver.onNext(GetBalanceResponse.newBuilder()
                .setBalance(toProto(balance))
                .build());
        responseObserver.onCompleted();
    }

    /** {@code UUID.fromString} throws a bare {@code IllegalArgumentException}, which
     * would be mapped to NOT_FOUND like an unknown account. */
    private static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException(field, value, e);
        }
    }

    private static Money toMoney(dev.treyer.sagapay.common.v1.Money money) {
        // Same reason as parseUuid.
        try {
            return Money.of(money.getAmount(), money.getCurrency());
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("amount", money.getAmount() + " " + money.getCurrency(), e);
        }
    }

    private static dev.treyer.sagapay.common.v1.Money toProto(Money money) {
        return dev.treyer.sagapay.common.v1.Money.newBuilder()
                .setCurrency(money.currency().getCurrencyCode())
                .setAmount(money.toPlainString())
                .build();
    }
}
