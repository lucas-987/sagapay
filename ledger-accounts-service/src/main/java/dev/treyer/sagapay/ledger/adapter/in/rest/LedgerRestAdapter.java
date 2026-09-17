package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.api.V1Api;
import dev.treyer.sagapay.ledger.application.port.in.GetWalletUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ListPostingsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.LookupAccountUseCase;
import dev.treyer.sagapay.ledger.domain.AccountLookup;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.WalletSnapshot;
import dev.treyer.sagapay.ledger.model.GetWallet200Response;
import dev.treyer.sagapay.ledger.model.ListPostings200Response;
import dev.treyer.sagapay.ledger.model.LookupAccount200Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Adapter in — REST, implements the interface generated from the OpenAPI spec.
 * Depends only on the {@code *UseCase} ports, never on {@code LedgerService}
 * directly.
 */
@RestController
public class LedgerRestAdapter implements V1Api {

    /** Only value produced today: {@code checkAndReserve}/{@code
     * releaseReservation} never write to {@code postings}, only {@code
     * postTransfer} does — the spec's other reason values have no matching row
     * yet, and {@code reason} isn't even a database column. */
    private static final String POSTING_REASON_TRANSFER_SETTLE = "TRANSFER_SETTLE";

    private final GetWalletUseCase getWalletUseCase;
    private final ListPostingsUseCase listPostingsUseCase;
    private final LookupAccountUseCase lookupAccountUseCase;

    public LedgerRestAdapter(GetWalletUseCase getWalletUseCase, ListPostingsUseCase listPostingsUseCase,
                              LookupAccountUseCase lookupAccountUseCase) {
        this.getWalletUseCase = getWalletUseCase;
        this.listPostingsUseCase = listPostingsUseCase;
        this.lookupAccountUseCase = lookupAccountUseCase;
    }

    @Override
    public ResponseEntity<GetWallet200Response> getWallet(UUID accountId) {
        WalletSnapshot wallet = getWalletUseCase.getWallet(accountId);
        return ResponseEntity.ok(new GetWallet200Response()
                .accountId(accountId.toString())
                .currency(wallet.balance().currency().getCurrencyCode())
                .balance(toMoney(wallet.balance()))
                .available(toMoney(wallet.available()))
                .held(toMoney(wallet.held())));
    }

    @Override
    public ResponseEntity<ListPostings200Response> listPostings(UUID accountId, OffsetDateTime from,
                                                                  String cursor, Integer limit) {
        PostingCursor after = cursor == null ? null : PostingCursor.decode(cursor);
        PostingPage page = listPostingsUseCase.listPostings(
                accountId, from == null ? null : from.toInstant(), after, limit);

        List<dev.treyer.sagapay.ledger.model.Posting> items = page.items().stream()
                .map(posting -> toRestPosting(posting, page.currency()))
                .toList();
        return ResponseEntity.ok(new ListPostings200Response()
                .items(items)
                .nextCursor(page.next() == null ? null : page.next().encode()));
    }

    @Override
    public ResponseEntity<LookupAccount200Response> lookupAccount(String handle) {
        AccountLookup lookup = lookupAccountUseCase.lookupByHandle(handle);
        return ResponseEntity.ok(new LookupAccount200Response()
                .accountId(lookup.accountId().toString())
                .displayName(lookup.displayName()));
    }

    // Not reusable from LedgerGrpcAdapter.toProto: dev.treyer.sagapay.ledger.model.Money
    // (OpenAPI-generated) and common.v1.Money (protoc-generated) are distinct
    // types despite the identical name.
    private static dev.treyer.sagapay.ledger.model.Money toMoney(Money money) {
        return new dev.treyer.sagapay.ledger.model.Money()
                .currency(money.currency().getCurrencyCode())
                .amount(money.toPlainString());
    }

    private static dev.treyer.sagapay.ledger.model.Posting toRestPosting(Posting posting, String currency) {
        return new dev.treyer.sagapay.ledger.model.Posting()
                .id(posting.getId().toString())
                .entryGroup(posting.getEntryGroup().toString())
                .leg(dev.treyer.sagapay.ledger.model.Posting.LegEnum.valueOf(posting.getLeg().name()))
                .reason(POSTING_REASON_TRANSFER_SETTLE)
                .amount(toMoney(Money.of(posting.getAmount(), currency)))
                .refTransferId(posting.getTransferId())
                .createdAt(posting.getCreatedAt().atOffset(java.time.ZoneOffset.UTC));
    }
}
