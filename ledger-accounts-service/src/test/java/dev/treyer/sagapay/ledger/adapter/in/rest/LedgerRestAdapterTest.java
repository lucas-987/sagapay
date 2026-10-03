package dev.treyer.sagapay.ledger.adapter.in.rest;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.ledger.application.port.in.GetWalletUseCase;
import dev.treyer.sagapay.ledger.application.port.in.ListPostingsUseCase;
import dev.treyer.sagapay.ledger.application.port.in.LookupAccountUseCase;
import dev.treyer.sagapay.ledger.domain.AccountLookup;
import dev.treyer.sagapay.ledger.domain.Posting;
import dev.treyer.sagapay.ledger.domain.PostingLeg;
import dev.treyer.sagapay.ledger.domain.PostingPage;
import dev.treyer.sagapay.ledger.domain.UnknownHandleException;
import dev.treyer.sagapay.ledger.domain.WalletSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Use cases are mocked, so no SQL runs here; security is tested separately. */
@WebMvcTest(LedgerRestAdapter.class)
@AutoConfigureMockMvc(addFilters = false)
class LedgerRestAdapterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetWalletUseCase getWalletUseCase;
    @MockitoBean
    private ListPostingsUseCase listPostingsUseCase;
    @MockitoBean
    private LookupAccountUseCase lookupAccountUseCase;

    @Test
    void getWalletReturnsWalletSnapshot() throws Exception {
        UUID accountId = UUID.randomUUID();
        WalletSnapshot snapshot = new WalletSnapshot(
                Money.of("100.00", "EUR"), Money.of("80.00", "EUR"), Money.of("20.00", "EUR"));
        when(getWalletUseCase.getWallet(accountId)).thenReturn(snapshot);

        mockMvc.perform(get("/v1/wallet").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amount").value("100.0000"))
                .andExpect(jsonPath("$.available.amount").value("80.0000"))
                .andExpect(jsonPath("$.held.amount").value("20.0000"));
    }

    @Test
    void getWalletWithoutAccountIdReturns400() throws Exception {
        mockMvc.perform(get("/v1/wallet"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWalletUnknownAccountReturns404WithAccountNotFoundCode() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(getWalletUseCase.getWallet(accountId))
                .thenThrow(new IllegalArgumentException("unknown account " + accountId));

        mockMvc.perform(get("/v1/wallet").param("accountId", accountId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void listPostingsReturnsItemsAndNextCursor() throws Exception {
        UUID accountId = UUID.randomUUID();
        Posting posting = new Posting(
                UUID.randomUUID(), accountId, "transfer-1", PostingLeg.DEBIT, new BigDecimal("5.0000"));
        PostingPage page = new PostingPage(List.of(posting), "EUR", null);
        when(listPostingsUseCase.listPostings(eq(accountId), isNull(), isNull(), anyInt()))
                .thenReturn(page);

        mockMvc.perform(get("/v1/wallet/postings").param("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].refTransferId").value("transfer-1"))
                .andExpect(jsonPath("$.items[0].reason").value("TRANSFER_SETTLE"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void listPostingsWithMalformedCursorReturns400WithInvalidCursorCode() throws Exception {
        UUID accountId = UUID.randomUUID();

        mockMvc.perform(get("/v1/wallet/postings")
                        .param("accountId", accountId.toString())
                        .param("cursor", "not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void lookupAccountReturnsAccountIdAndDisplayName() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(lookupAccountUseCase.lookupByHandle("alice")).thenReturn(new AccountLookup(accountId, "Alice Martin"));

        mockMvc.perform(get("/v1/users/lookup").param("handle", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.displayName").value("Alice Martin"));
    }

    @Test
    void lookupAccountUnknownHandleReturns404WithRecipientNotFoundCode() throws Exception {
        when(lookupAccountUseCase.lookupByHandle("nobody")).thenThrow(new UnknownHandleException("nobody"));

        mockMvc.perform(get("/v1/users/lookup").param("handle", "nobody"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECIPIENT_NOT_FOUND"));
    }
}
