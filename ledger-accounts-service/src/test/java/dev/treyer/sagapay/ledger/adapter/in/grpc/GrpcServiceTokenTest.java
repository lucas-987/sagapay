package dev.treyer.sagapay.ledger.adapter.in.grpc;

import dev.treyer.sagapay.common.v1.Money;
import dev.treyer.sagapay.ledger.TestcontainersConfiguration;
import dev.treyer.sagapay.ledger.adapter.out.persistence.AccountRepository;
import dev.treyer.sagapay.ledger.adapter.out.persistence.ReservationRepository;
import dev.treyer.sagapay.ledger.domain.Account;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import dev.treyer.sagapay.ledger.security.KeySetServer;
import dev.treyer.sagapay.ledger.security.TestTokens;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outside {@code local-noauth} the gRPC API admits a service token only. The keys are served by a
 * local HTTP server standing in for the identity provider.
 */
@SpringBootTest
@AutoConfigureTestGrpcTransport
@Import(TestcontainersConfiguration.class)
class GrpcServiceTokenTest {

    private static final String RECIPIENT = UUID.randomUUID().toString();

    private static final KeySetServer IDENTITY_PROVIDER = new KeySetServer();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDENTITY_PROVIDER::url);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDENTITY_PROVIDER.stop();
    }

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ReservationRepository reservations;

    private UUID newAccount() {
        return accounts.save(new Account("test-" + UUID.randomUUID(), "Test User", "EUR", new BigDecimal("100.0000")))
                .getId();
    }

    private CheckAndReserveResponse reserve(String transferId, UUID accountId, String authorization) {
        Metadata headers = new Metadata();
        if (authorization != null) {
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        }
        return LedgerServiceGrpc.newBlockingStub(channelFactory.createChannel("test"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .checkAndReserve(CheckAndReserveRequest.newBuilder()
                        .setTransferId(transferId)
                        .setFromAccountId(accountId.toString())
                        .setToAccountId(RECIPIENT)
                        .setAmount(Money.newBuilder()
                                .setCurrency("EUR")
                                .setAmount("40.00")
                                .build())
                        .build());
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("no token", null, Status.Code.UNAUTHENTICATED),
                Arguments.of("another scheme", "Basic dXNlcjpwYXNz", Status.Code.UNAUTHENTICATED),
                Arguments.of("a scheme with no token and no space", "Bearer", Status.Code.UNAUTHENTICATED),
                Arguments.of("a token with whitespace inside", "Bearer not a jwt", Status.Code.UNAUTHENTICATED),
                Arguments.of("a malformed token", "Bearer not-a-jwt", Status.Code.UNAUTHENTICATED),
                Arguments.of(
                        "a token signed by another key",
                        "Bearer " + TestTokens.token().signedByAnotherKey().build(),
                        Status.Code.UNAUTHENTICATED),
                Arguments.of(
                        "a token from another issuer",
                        "Bearer "
                                + TestTokens.token()
                                        .issuer("https://other-issuer.test/realms/sagapay")
                                        .build(),
                        Status.Code.UNAUTHENTICATED),
                Arguments.of(
                        "an expired token",
                        "Bearer " + TestTokens.token().expired().build(),
                        Status.Code.UNAUTHENTICATED),
                Arguments.of(
                        "a token for another audience",
                        "Bearer " + TestTokens.token().audience("api-gateway").build(),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "a token with no audience",
                        "Bearer " + TestTokens.token().audience().build(),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "a token without the scope",
                        "Bearer " + TestTokens.token().scope("openid profile").build(),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "a token with no scope claim",
                        "Bearer " + TestTokens.token().scope(null).build(),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "a token with an empty scope",
                        "Bearer " + TestTokens.token().scope("").build(),
                        Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "a token whose scope only starts like the required one",
                        "Bearer " + TestTokens.token().scope("ledger:writeall").build(),
                        Status.Code.PERMISSION_DENIED));
    }

    @ParameterizedTest(name = "{0} is refused with {2} and nothing is written")
    @MethodSource("refusals")
    void aRefusedCallWritesNothing(String ignoredName, String authorization, Status.Code expected) {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> reserve(transferId, accountId, authorization))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(expected));

        assertThat(reservations.findByTransferId(transferId)).isEmpty();
        assertThat(accounts.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("100.0000");
        assertThat(reservations.sumAmountByAccountIdAndStatus(accountId, ReservationStatus.ACTIVE))
                .isEqualByComparingTo("0");
    }

    /**
     * Spring gRPC builds a token object from the header and refuses an empty value with an
     * {@code IllegalArgumentException}, which the ledger's advice maps like any other: the status
     * is not the one of an authentication failure, so only the refusal is asserted.
     */
    @ParameterizedTest(name = "a bearer scheme with {0} is refused and nothing is written")
    @MethodSource("emptyBearerTokens")
    void anEmptyBearerTokenIsRefusedBeforeTheRpc(String ignoredName, String authorization) {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> reserve(transferId, accountId, authorization))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(reservations.findByTransferId(transferId)).isEmpty();
    }

    static Stream<Arguments> emptyBearerTokens() {
        return Stream.of(Arguments.of("no token", "Bearer "), Arguments.of("a blank token", "Bearer    "));
    }

    static Stream<Arguments> acceptedCalls() {
        return Stream.of(
                Arguments.of("a token with the audience and the scope", "Bearer " + TestTokens.token().build()),
                Arguments.of(
                        "the audience among several and the scope among several",
                        "Bearer "
                                + TestTokens.token()
                                        .audience("account", "ledger-accounts-service")
                                        .scope("profile ledger:write email")
                                        .build()),
                Arguments.of("the bearer scheme in lower case", "bearer " + TestTokens.token().build()));
    }

    @ParameterizedTest(name = "{0} reaches the RPC")
    @MethodSource("acceptedCalls")
    void aCallThatMeetsEveryRequirementReachesTheRpc(String ignoredName, String authorization) {
        UUID accountId = newAccount();
        String transferId = UUID.randomUUID().toString();

        CheckAndReserveResponse response = reserve(transferId, accountId, authorization);

        assertThat(response.getStatus()).isEqualTo(CheckAndReserveResponse.Status.OK);
        assertThat(reservations.findByTransferId(transferId)).isPresent();
    }

    @Test
    void theKeysAreNotReadAgainOnceCached() {
        UUID accountId = newAccount();
        reserve(
                UUID.randomUUID().toString(),
                accountId,
                "Bearer " + TestTokens.token().build());
        int readsBefore = IDENTITY_PROVIDER.reads();

        for (int i = 0; i < 3; i++) {
            reserve(
                    UUID.randomUUID().toString(),
                    accountId,
                    "Bearer " + TestTokens.token().build());
        }

        assertThat(IDENTITY_PROVIDER.reads() - readsBefore).isZero();
    }
}
