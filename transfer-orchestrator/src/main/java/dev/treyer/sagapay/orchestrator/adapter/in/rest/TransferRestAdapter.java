package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.application.port.in.AdvanceSagaUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ConfirmTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.GetTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.InitiateTransferUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.ListTransfersUseCase;
import dev.treyer.sagapay.orchestrator.application.port.out.AccountLookupPort;
import dev.treyer.sagapay.orchestrator.domain.MalformedRequestException;
import dev.treyer.sagapay.orchestrator.domain.RecipientNotFoundException;
import dev.treyer.sagapay.orchestrator.domain.SagaStep;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferCursor;
import dev.treyer.sagapay.transfer.api.V1Api;
import dev.treyer.sagapay.transfer.model.ConfirmBlockedTransferRequest;
import dev.treyer.sagapay.transfer.model.CreateTransferRequest;
import dev.treyer.sagapay.transfer.model.ListTransfers200Response;
import dev.treyer.sagapay.transfer.model.TransferDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Adapter in — REST, implements the interface generated from the OpenAPI spec.
 * Depends only on the {@code *UseCase} ports, never on {@code SagaService}
 * directly (same rule as the ledger's {@code LedgerRestAdapter}).
 *
 * <p>{@code POST /v1/requests} is intentionally not overridden — {@code
 * V1Api}'s default returns 501, matching the checklist's decision that it's cut
 * from the whole MVP, not just deferred.
 *
 * <p>{@code X-User-Id}: a dev/local stand-in for the JWT {@code sub} claim
 * (there's no Keycloak wired yet, M4). Every {@code CreateTransferRequest.
 * toUserId}/resolved {@code toHandle} is treated as *both* a user id and an
 * account id: the ledger fuses the two (an {@code Account} carries its own
 * {@code handle}/{@code displayName}, no separate user entity), so {@code
 * transfers.sender_id == transfers.sender_account_id} in this MVP even though
 * the schema keeps them as distinct columns.
 */
@RestController
public class TransferRestAdapter implements V1Api {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final InitiateTransferUseCase initiateTransferUseCase;
    private final AdvanceSagaUseCase advanceSagaUseCase;
    private final ListTransfersUseCase listTransfersUseCase;
    private final GetTransferUseCase getTransferUseCase;
    private final ConfirmTransferUseCase confirmTransferUseCase;
    private final AccountLookupPort accountLookupPort;
    private final Executor executor;

    public TransferRestAdapter(InitiateTransferUseCase initiateTransferUseCase, AdvanceSagaUseCase advanceSagaUseCase,
                                ListTransfersUseCase listTransfersUseCase, GetTransferUseCase getTransferUseCase,
                                ConfirmTransferUseCase confirmTransferUseCase, AccountLookupPort accountLookupPort,
                                @Qualifier("applicationTaskExecutor") Executor applicationTaskExecutor) {
        this.initiateTransferUseCase = initiateTransferUseCase;
        this.advanceSagaUseCase = advanceSagaUseCase;
        this.listTransfersUseCase = listTransfersUseCase;
        this.getTransferUseCase = getTransferUseCase;
        this.confirmTransferUseCase = confirmTransferUseCase;
        this.accountLookupPort = accountLookupPort;
        this.executor = applicationTaskExecutor;
    }

    @Override
    public ResponseEntity<dev.treyer.sagapay.transfer.model.Transfer> createTransfer(
            UUID idempotencyKey, CreateTransferRequest request) {
        UUID senderId = requireUserId();
        UUID recipientId = resolveRecipient(request);
        Money amount = toMoney(request.getAmount());

        InitiateTransferUseCase.Result result = initiateTransferUseCase.initiateTransfer(
                senderId, senderId, recipientId, recipientId, amount, idempotencyKey, request.getNote());

        if (result.created()) {
            // "en tâche de fond" (checklist intro): the client sees 202 with
            // status=INITIATED right away, doesn't wait for the ledger round
            // trip(s) advance() makes. SagaService.advance() itself stays
            // synchronous (directly unit-testable, see SagaServiceTest) --
            // dispatching it off-thread is this adapter's job, not the
            // domain's.
            UUID transferId = result.transfer().getId();
            executor.execute(() -> advanceSagaUseCase.advance(transferId));
        }

        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(toRestTransfer(result.transfer()));
    }

    @Override
    public ResponseEntity<ListTransfers200Response> listTransfers(
            String direction, dev.treyer.sagapay.transfer.model.TransferStatus status, String cursor, Integer limit) {
        UUID userId = requireUserId();
        ListTransfersUseCase.Direction dir = ListTransfersUseCase.Direction.valueOf(direction);
        TransferCursor after = cursor == null ? null : TransferCursor.decode(cursor);

        dev.treyer.sagapay.orchestrator.domain.TransferStatus domainStatus;
        try {
            domainStatus = status == null ? null : dev.treyer.sagapay.orchestrator.domain.TransferStatus.valueOf(status.name());
        } catch (IllegalArgumentException e) {
            // A status this MVP can't reach yet (SCREENING, BLOCKED, ...) -- a
            // valid filter, just one with no possible matches, not a 400.
            return ResponseEntity.ok(new ListTransfers200Response().items(List.of()).nextCursor(null));
        }

        ListTransfersUseCase.Page page = listTransfersUseCase.listTransfers(userId, dir, domainStatus, after, limit);
        List<dev.treyer.sagapay.transfer.model.Transfer> items = page.items().stream().map(this::toRestTransfer).toList();
        return ResponseEntity.ok(new ListTransfers200Response()
                .items(items)
                .nextCursor(page.next() == null ? null : page.next().encode()));
    }

    @Override
    public ResponseEntity<TransferDetail> getTransfer(UUID id) {
        GetTransferUseCase.TransferWithSteps result = getTransferUseCase.getTransfer(id);
        Transfer transfer = result.transfer();

        TransferDetail detail = new TransferDetail(transfer.getId(), transfer.getSenderId().toString(),
                toRestMoney(transfer), toRestStatus(transfer), transfer.getCreatedAt().atOffset(ZoneOffset.UTC))
                .recipientId(transfer.getRecipientId().toString())
                .note(transfer.getNote())
                .failureReason(transfer.getFailureReason())
                .updatedAt(transfer.getUpdatedAt().atOffset(ZoneOffset.UTC))
                .steps(result.steps().stream().map(this::toRestStep).toList())
                // No fraud service exists in M2 -- nullable in the spec, not an omission.
                .fraud(null);
        return ResponseEntity.ok(detail);
    }

    @Override
    public ResponseEntity<dev.treyer.sagapay.transfer.model.Transfer> confirmBlockedTransfer(
            UUID id, ConfirmBlockedTransferRequest request) {
        confirmTransferUseCase.confirmTransfer(id, request.getVerificationToken());
        throw new IllegalStateException("unreachable: confirmTransfer always throws in M2 (no BLOCKED transfer exists yet)");
    }

    private UUID requireUserId() {
        // Not V1Api.getRequest(): its default implementation always returns
        // Optional.empty() -- it's an extension point implementors may
        // override, not something Spring populates automatically.
        // RequestContextHolder is the standard way to reach the current HTTP
        // request from within a request-handling thread.
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        String value = attributes.getRequest().getHeader(USER_ID_HEADER);
        if (value == null) {
            throw new MalformedRequestException("missing " + USER_ID_HEADER
                    + " header (dev/local stand-in for the JWT subject, until M4 wires real auth)");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException(USER_ID_HEADER, value, e);
        }
    }

    /** {@code toUserId} used as-is; {@code toHandle} (e.g. {@code @bob})
     * resolved via the ledger's own handle lookup (§8 stretch scope). */
    private UUID resolveRecipient(CreateTransferRequest request) {
        if (request.getToUserId() != null) {
            try {
                return UUID.fromString(request.getToUserId());
            } catch (IllegalArgumentException e) {
                throw new MalformedRequestException("toUserId", request.getToUserId(), e);
            }
        }
        if (request.getToHandle() != null) {
            String handle = request.getToHandle().startsWith("@")
                    ? request.getToHandle().substring(1) : request.getToHandle();
            return accountLookupPort.lookupByHandle(handle)
                    .orElseThrow(() -> new RecipientNotFoundException(request.getToHandle()));
        }
        throw new MalformedRequestException("must provide either toUserId or toHandle");
    }

    private static Money toMoney(dev.treyer.sagapay.transfer.model.Money money) {
        try {
            return Money.of(money.getAmount(), money.getCurrency());
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("amount", money.getAmount() + " " + money.getCurrency(), e);
        }
    }

    private static dev.treyer.sagapay.transfer.model.Money toRestMoney(Transfer transfer) {
        return new dev.treyer.sagapay.transfer.model.Money(transfer.getCurrency(), transfer.getAmount().toPlainString());
    }

    private static dev.treyer.sagapay.transfer.model.TransferStatus toRestStatus(Transfer transfer) {
        return dev.treyer.sagapay.transfer.model.TransferStatus.valueOf(transfer.getStatus().name());
    }

    private dev.treyer.sagapay.transfer.model.Transfer toRestTransfer(Transfer transfer) {
        return new dev.treyer.sagapay.transfer.model.Transfer(transfer.getId(), transfer.getSenderId().toString(),
                toRestMoney(transfer), toRestStatus(transfer), transfer.getCreatedAt().atOffset(ZoneOffset.UTC))
                .recipientId(transfer.getRecipientId().toString())
                .note(transfer.getNote())
                .failureReason(transfer.getFailureReason())
                .updatedAt(transfer.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }

    private dev.treyer.sagapay.transfer.model.SagaStep toRestStep(SagaStep step) {
        return new dev.treyer.sagapay.transfer.model.SagaStep()
                .at(step.getAt().atOffset(ZoneOffset.UTC))
                .step(step.getStep())
                .outcome(step.getOutcome());
    }
}
