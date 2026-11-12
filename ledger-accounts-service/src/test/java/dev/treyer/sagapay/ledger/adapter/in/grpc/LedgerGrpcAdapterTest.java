package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.common.v1.Money;
import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.security.TestJwtConfiguration;
import dev.treyer.sagapay.ledger.security.TestTokens;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.GetBalanceRequest;
import dev.treyer.sagapay.ledger.v1.GetBalanceResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The RPCs, called with a valid service token; the lock itself is tested by
 * {@code GrpcServiceTokenTest}.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class LedgerGrpcAdapterTest {

    private static final String RECIPIENT = UUID.randomUUID().toString();

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private AccountRepository accounts;

    private LedgerServiceGrpc.LedgerServiceBlockingStub client;

    @BeforeEach
    void setUp() {
        client = LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"))
                .withInterceptors(TestTokens.serviceCredentials());
    }

    private UUID newAccount(String balance) {
        return accounts.save(new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal(balance)))
                .getId();
    }

    @Test
    void getBalanceReturnsAccountBalance() {
        UUID accountId = newAccount("42.5000");

        GetBalanceResponse response = client.getBalance(GetBalanceRequest.newBuilder()
                .setAccountId(accountId.toString())
                .build());

        assertThat(response.getBalance().getAmount()).isEqualTo("42.5000");
        assertThat(response.getBalance().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void getBalanceUnknownAccountReturnsNotFound() {
        String unknownId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> client.getBalance(
                        GetBalanceRequest.newBuilder().setAccountId(unknownId).build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void getBalanceMalformedAccountIdReturnsInvalidArgument() {
        assertThatThrownBy(() -> client.getBalance(GetBalanceRequest.newBuilder()
                        .setAccountId("not-a-uuid")
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void checkAndReserveSufficientFundsReturnsOk() {
        UUID accountId = newAccount("100.0000");

        CheckAndReserveResponse response = client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(accountId.toString())
                .setToAccountId(RECIPIENT)
                .setAmount(
                        Money.newBuilder().setCurrency("EUR").setAmount("40.00").build())
                .build());

        assertThat(response.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
        assertThat(response.getReservationId()).isNotBlank();
        assertThat(response.getExpiresAt().getSeconds())
                .isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void checkAndReserveCurrencyMismatchReturnsInvalidArgument() {
        UUID accountId = newAccount("100.0000");

        assertThatThrownBy(() -> client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(UUID.randomUUID().toString())
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(RECIPIENT)
                        .setAmount(Money.newBuilder()
                                .setCurrency("USD")
                                .setAmount("10.00")
                                .build())
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void checkAndReserveOverPrecisionAmountReturnsInvalidArgument() {
        UUID accountId = newAccount("100.0000");

        assertThatThrownBy(() -> client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(UUID.randomUUID().toString())
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(RECIPIENT)
                        .setAmount(Money.newBuilder()
                                .setCurrency("EUR")
                                .setAmount("10.12345")
                                .build())
                        .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("INVALID_ARGUMENT");
    }

    @Test
    void checkAndReserveInsufficientFundsReturnsInsufficientFundsStatus() {
        UUID accountId = newAccount("10.0000");

        CheckAndReserveResponse response = client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(accountId.toString())
                .setToAccountId(RECIPIENT)
                .setAmount(
                        Money.newBuilder().setCurrency("EUR").setAmount("40.00").build())
                .build());

        assertThat(response.getStatus()).isEqualTo(CheckAndReserveResponse.Status.INSUFFICIENT_FUNDS);
    }

    @Test
    void checkAndReserveThenPostTransferMovesFundsBetweenAccounts() {
        UUID from = newAccount("100.0000");
        UUID to = newAccount("0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.newBuilder().setCurrency("EUR").setAmount("30.00").build();

        CheckAndReserveResponse reserve = client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId)
                .setFromAccountId(from.toString())
                .setToAccountId(to.toString())
                .setAmount(amount)
                .build());
        assertThat(reserve.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        PostTransferResponse posted = client.postTransfer(PostTransferRequest.newBuilder()
                .setTransferId(transferId)
                .setFromAccountId(from.toString())
                .setToAccountId(to.toString())
                .setAmount(amount)
                .build());
        assertThat(posted.getPosted()).isTrue();

        GetBalanceResponse fromBalance = client.getBalance(
                GetBalanceRequest.newBuilder().setAccountId(from.toString()).build());
        GetBalanceResponse toBalance = client.getBalance(
                GetBalanceRequest.newBuilder().setAccountId(to.toString()).build());
        assertThat(fromBalance.getBalance().getAmount()).isEqualTo("70.0000");
        assertThat(toBalance.getBalance().getAmount()).isEqualTo("30.0000");
    }

    @Test
    void releaseReservationFreesTheHoldForANewReservation() {
        UUID accountId = newAccount("100.0000");
        String transferId = UUID.randomUUID().toString();
        CheckAndReserveResponse reserve = client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId)
                .setFromAccountId(accountId.toString())
                .setToAccountId(RECIPIENT)
                .setAmount(
                        Money.newBuilder().setCurrency("EUR").setAmount("40.00").build())
                .build());
        assertThat(reserve.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);

        client.releaseReservation(ReleaseReservationRequest.newBuilder()
                .setTransferId(transferId)
                .setReservationId(reserve.getReservationId())
                .build());

        // GetBalance has no held amount, so the release shows indirectly: 40 + 90 > 100
        // fits only once the first hold is gone.
        CheckAndReserveResponse secondReserve = client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(accountId.toString())
                .setToAccountId(RECIPIENT)
                .setAmount(
                        Money.newBuilder().setCurrency("EUR").setAmount("90.00").build())
                .build());
        assertThat(secondReserve.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
    }

    @Test
    void postTransferToAnotherRecipientThanReservedReturnsNotFound() {
        UUID from = newAccount("100.0000");
        UUID reservedTo = newAccount("0.0000");
        UUID otherTo = newAccount("0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.newBuilder().setCurrency("EUR").setAmount("30.00").build();
        client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId)
                .setFromAccountId(from.toString())
                .setToAccountId(reservedTo.toString())
                .setAmount(amount)
                .build());

        assertThatThrownBy(() -> client.postTransfer(PostTransferRequest.newBuilder()
                        .setTransferId(transferId)
                        .setFromAccountId(from.toString())
                        .setToAccountId(otherTo.toString())
                        .setAmount(amount)
                        .build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(e.getStatus().getDescription())
                            .isEqualTo("no ACTIVE reservation for transferId " + transferId + " matching account "
                                    + from + ", recipient " + otherTo + " and amount 30.0000");
                });

        assertThat(client.getBalance(GetBalanceRequest.newBuilder()
                                .setAccountId(from.toString())
                                .build())
                        .getBalance()
                        .getAmount())
                .isEqualTo("100.0000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void checkAndReserveWithoutAValidRecipientReturnsInvalidArgument(String recipient) {
        UUID accountId = newAccount("100.0000");

        assertThatThrownBy(() -> client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(UUID.randomUUID().toString())
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(recipient)
                        .setAmount(Money.newBuilder()
                                .setCurrency("EUR")
                                .setAmount("10.00")
                                .build())
                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void checkAndReserveReplayedWithAnotherRecipientReturnsAlreadyExists() {
        UUID from = newAccount("100.0000");
        UUID reservedTo = newAccount("0.0000");
        UUID otherTo = newAccount("0.0000");
        String transferId = UUID.randomUUID().toString();
        Money amount = Money.newBuilder().setCurrency("EUR").setAmount("30.00").build();
        client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                .setTransferId(transferId)
                .setFromAccountId(from.toString())
                .setToAccountId(reservedTo.toString())
                .setAmount(amount)
                .build());

        assertThatThrownBy(() -> client.checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(transferId)
                        .setFromAccountId(from.toString())
                        .setToAccountId(otherTo.toString())
                        .setAmount(amount)
                        .build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
                    assertThat(e.getStatus().getDescription())
                            .isEqualTo("transferId " + transferId + " already used with different parameters than"
                                    + " account " + from + " / recipient " + otherTo + " / amount 30.0000");
                });
    }
}
