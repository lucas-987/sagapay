package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.api.V1Api;
import dev.treyer.sagapay.ledger.application.port.in.GetWalletUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ListPostingsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.LookupAccountNamesUseCase;
import dev.treyer.sagapay.ledger.application.port.in.LookupAccountUseCase;
import dev.treyer.sagapay.ledger.domain.AccountLookup;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingCursor;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.WalletSnapshot;
import dev.treyer.sagapay.ledger.model.AccountName;
import dev.treyer.sagapay.ledger.model.GetWallet200Response;
import dev.treyer.sagapay.ledger.model.ListPostings200Response;
import dev.treyer.sagapay.ledger.model.LookupAccount200Response;
import dev.treyer.sagapay.ledger.model.LookupAccountNames200Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class LedgerRestAdapter implements V1Api {

    /** Only {@code postTransfer} writes postings, so it is the only reason a posting
     * can have; it is not stored. */
    private static final String POSTING_REASON_TRANSFER_SETTLE = "TRANSFER_SETTLE";

    private static final int MAX_NAMES_IDS = 50;
    private static final String NOT_UUIDS = "ids must all be UUIDs";

    private final GetWalletUseCase getWalletUseCase;
    private final ListPostingsUseCase listPostingsUseCase;
    private final LookupAccountUseCase lookupAccountUseCase;
    private final LookupAccountNamesUseCase lookupAccountNamesUseCase;
    private final CallerAccount callerAccount;

    public LedgerRestAdapter(
            GetWalletUseCase getWalletUseCase,
            ListPostingsUseCase listPostingsUseCase,
            LookupAccountUseCase lookupAccountUseCase,
            LookupAccountNamesUseCase lookupAccountNamesUseCase,
            CallerAccount callerAccount) {
        this.getWalletUseCase = getWalletUseCase;
        this.listPostingsUseCase = listPostingsUseCase;
        this.lookupAccountUseCase = lookupAccountUseCase;
        this.lookupAccountNamesUseCase = lookupAccountNamesUseCase;
        this.callerAccount = callerAccount;
    }

    @Override
    public ResponseEntity<GetWallet200Response> getWallet() {
        UUID accountId = callerAccount.id();
        WalletSnapshot wallet = getWalletUseCase.getWallet(accountId);
        return ResponseEntity.ok(new GetWallet200Response()
                .accountId(accountId.toString())
                .currency(wallet.balance().currency().getCurrencyCode())
                .balance(toMoney(wallet.balance()))
                .available(toMoney(wallet.available()))
                .held(toMoney(wallet.held())));
    }

    @Override
    public ResponseEntity<ListPostings200Response> listPostings(OffsetDateTime from, String cursor, Integer limit) {
        UUID accountId = callerAccount.id();
        PostingCursor after = cursor == null ? null : PostingCursorCodec.decode(cursor);
        PostingPage page =
                listPostingsUseCase.listPostings(accountId, from == null ? null : from.toInstant(), after, limit);

        List<dev.treyer.sagapay.ledger.model.Posting> items = page.items().stream()
                .map(posting -> toRestPosting(posting, page.currency()))
                .toList();
        return ResponseEntity.ok(new ListPostings200Response()
                .items(items)
                .nextCursor(page.next() == null ? null : PostingCursorCodec.encode(page.next())));
    }

    @Override
    public ResponseEntity<LookupAccount200Response> lookupAccount(String handle) {
        AccountLookup lookup = lookupAccountUseCase.lookupByHandle(handle);
        return ResponseEntity.ok(new LookupAccount200Response()
                .accountId(lookup.accountId().toString())
                .displayName(lookup.displayName()));
    }

    @Override
    public ResponseEntity<LookupAccountNames200Response> lookupAccountNames(List<String> ids) {
        Set<UUID> accountIds = parseAccountIds(ids);
        List<AccountName> items = lookupAccountNamesUseCase.lookupNames(accountIds).stream()
                .map(name -> new AccountName().accountId(name.accountId()).displayName(name.displayName()))
                .toList();
        return ResponseEntity.ok(new LookupAccountNames200Response().items(items));
    }

    private static Set<UUID> parseAccountIds(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_NAMES_IDS) {
            throw new InvalidAccountIdsException("ids must hold between 1 and " + MAX_NAMES_IDS + " account ids");
        }
        Set<UUID> accountIds = new LinkedHashSet<>();
        for (String id : ids) {
            try {
                UUID accountId = UUID.fromString(id);
                // The JDK also reads short forms such as 1-1-1-1-1; only the 36-character form is an id.
                if (!accountId.toString().equalsIgnoreCase(id)) {
                    throw new InvalidAccountIdsException(NOT_UUIDS);
                }
                accountIds.add(accountId);
            } catch (IllegalArgumentException e) {
                throw new InvalidAccountIdsException(NOT_UUIDS, e);
            }
        }
        return accountIds;
    }

    // The OpenAPI and protobuf Money classes are distinct types despite the name.
    private static dev.treyer.sagapay.ledger.model.Money toMoney(Money money) {
        return new dev.treyer.sagapay.ledger.model.Money()
                .currency(money.currency().getCurrencyCode())
                .amount(money.toPlainString());
    }

    private static dev.treyer.sagapay.ledger.model.Posting toRestPosting(Posting posting, String currency) {
        return new dev.treyer.sagapay.ledger.model.Posting()
                .id(posting.getId().toString())
                .entryGroup(posting.getEntryGroup().toString())
                .leg(dev.treyer.sagapay.ledger.model.Posting.LegEnum.valueOf(
                        posting.getLeg().name()))
                .reason(POSTING_REASON_TRANSFER_SETTLE)
                .amount(toMoney(Money.of(posting.getAmount(), currency)))
                .refTransferId(posting.getTransferId())
                .createdAt(posting.getCreatedAt().atOffset(java.time.ZoneOffset.UTC));
    }
}
