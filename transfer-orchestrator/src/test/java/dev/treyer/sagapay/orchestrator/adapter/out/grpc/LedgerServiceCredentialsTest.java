package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.v1.Empty;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.config.LedgerAuthProperties;
import dev.treyer.sagapay.orchestrator.config.LedgerProperties;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The orchestrator's credential on the ledger calls, against an in-process ledger that records the
 * {@code authorization} header of each call and answers with the status the test sets.
 */
class LedgerServiceCredentialsTest {

    private static final String TOKEN_URL = "http://idp.test/token";
    private static final String CLIENT_SECRET = "the-client-secret";
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** One ledger call, by the RPC it makes. */
    private enum Rpc {
        CHECK_AND_RESERVE(adapter ->
                adapter.checkAndReserve("t-1", UUID.randomUUID(), UUID.randomUUID(), Money.of("1.00", "EUR"))),
        POST_TRANSFER(
                adapter -> adapter.postTransfer("t-1", UUID.randomUUID(), UUID.randomUUID(), Money.of("1.00", "EUR"))),
        RELEASE_RESERVATION(adapter -> adapter.releaseReservation("t-1", UUID.randomUUID()));

        private final Consumer<LedgerGrpcClientAdapter> call;

        Rpc(Consumer<LedgerGrpcClientAdapter> call) {
            this.call = call;
        }
    }

    private final List<String> authorizations = new ArrayList<>();
    private final List<ILoggingEvent> logs = new ArrayList<>();
    private ListAppender<ILoggingEvent> appender;
    private Status answer = Status.OK;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startLedger() throws Exception {
        String name = "ledger-" + UUID.randomUUID();
        ServerInterceptor recordAndAnswer = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                authorizations.add(headers.get(AUTHORIZATION));
                if (!answer.isOk()) {
                    call.close(answer, new Metadata());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new LedgerServiceGrpc.LedgerServiceImplBase() {
                            @Override
                            public void checkAndReserve(
                                    CheckAndReserveRequest request,
                                    StreamObserver<CheckAndReserveResponse> responseObserver) {
                                responseObserver.onNext(CheckAndReserveResponse.newBuilder()
                                        .setStatus(CheckAndReserveResponse.Status.OK)
                                        .setReservationId(UUID.randomUUID().toString())
                                        .setExpiresAt(com.google.protobuf.Timestamp.newBuilder()
                                                .setSeconds(Instant.now().getEpochSecond() + 300))
                                        .build());
                                responseObserver.onCompleted();
                            }

                            @Override
                            public void postTransfer(
                                    PostTransferRequest request,
                                    StreamObserver<PostTransferResponse> responseObserver) {
                                responseObserver.onNext(PostTransferResponse.getDefaultInstance());
                                responseObserver.onCompleted();
                            }

                            @Override
                            public void releaseReservation(
                                    ReleaseReservationRequest request, StreamObserver<Empty> responseObserver) {
                                responseObserver.onNext(Empty.getDefaultInstance());
                                responseObserver.onCompleted();
                            }
                        },
                        recordAndAnswer))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @AfterEach
    void stopLedger() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        SecurityContextHolder.clearContext();
        channel.shutdownNow();
        server.shutdownNow();
    }

    private LedgerGrpcClientAdapter adapterWith(ServiceTokenProvider tokens) {
        return adapterWith(tokens, 5000);
    }

    private LedgerGrpcClientAdapter adapterWith(ServiceTokenProvider tokens, long deadlineMs) {
        return new LedgerGrpcClientAdapter(
                channel, new LedgerProperties(new LedgerProperties.Grpc(deadlineMs)), tokens);
    }

    private static ServiceTokenProvider fixed(String token) {
        return new FixedServiceTokenProvider(token);
    }

    /** The real provider against a fake identity provider, which gives the next token of the list. */
    private SpringServiceTokenProvider providerFetching(String... tokens) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer idp = MockRestServiceServer.bindTo(
                        SpringServiceTokenProvider.withTokenConverters(builder))
                .build();
        for (String token : tokens) {
            idp.expect(once(), requestTo(TOKEN_URL))
                    .andRespond(withSuccess(
                            "{\"access_token\":\"%s\",\"token_type\":\"Bearer\",\"expires_in\":300}".formatted(token),
                            MediaType.APPLICATION_JSON));
        }
        return new SpringServiceTokenProvider(
                builder.build(),
                new LedgerAuthProperties("transfer-orchestrator", CLIENT_SECRET, URI.create(TOKEN_URL), 30_000, 2000),
                Clock.systemUTC());
    }

    /** The provider whose identity provider refuses the client. */
    private SpringServiceTokenProvider providerRefused() {
        return providerRefusedWith("");
    }

    /** The same, the identity provider's error body being {@code body}. */
    private SpringServiceTokenProvider providerRefusedWith(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(SpringServiceTokenProvider.withTokenConverters(builder))
                .build()
                .expect(once(), requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(body));
        return new SpringServiceTokenProvider(
                builder.build(),
                new LedgerAuthProperties("transfer-orchestrator", CLIENT_SECRET, URI.create(TOKEN_URL), 30_000, 2000),
                Clock.systemUTC());
    }

    private static void callerIsUser(String userToken) {
        Jwt jwt = Jwt.withTokenValue(userToken)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @ParameterizedTest(name = "{0} carries the service token")
    @EnumSource(Rpc.class)
    void everyLedgerCallCarriesTheServiceToken(Rpc rpc) {
        rpc.call.accept(adapterWith(fixed("service-token")));

        assertThat(authorizations).containsExactly("Bearer service-token");
    }

    @ParameterizedTest(name = "{0} reaches the ledger although the token took longer than the call deadline")
    @EnumSource(Rpc.class)
    void theTokenIsFetchedBeforeTheCallDeadlineStarts(Rpc rpc) {
        ServiceTokenProvider slow = new ServiceTokenProvider() {
            @Override
            public String token() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "slow-token";
            }

            @Override
            public void drop() {}
        };

        rpc.call.accept(adapterWith(slow, 500));

        assertThat(authorizations).containsExactly("Bearer slow-token");
    }

    @ParameterizedTest(name = "{0} carries the service token, not the user's, while a user's request is handled")
    @EnumSource(Rpc.class)
    void theUsersTokenNeverReachesTheLedger(Rpc rpc) {
        callerIsUser("user-token");

        rpc.call.accept(adapterWith(fixed("service-token")));

        assertThat(authorizations).containsExactly("Bearer service-token");
    }

    static Stream<Arguments> credentialRefusals() {
        return Stream.of(Rpc.values())
                .flatMap(rpc -> Stream.of(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED)
                        .map(code -> Arguments.of(rpc, code)));
    }

    @ParameterizedTest(name = "{0} refused with {1} is an outage, not a refusal")
    @MethodSource("credentialRefusals")
    void aCredentialRefusalOfTheLedgerIsAnOutage(Rpc rpc, Status.Code code) {
        answer = code.toStatus().withDescription("token refused");

        assertThatThrownBy(() -> rpc.call.accept(adapterWith(fixed("service-token"))))
                .isInstanceOfSatisfying(LedgerUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("ledger unavailable");
                    assertThat(e.getCause()).isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                        assertThat(cause.getStatus().getCode()).isEqualTo(code);
                        assertThat(cause.getStatus().getDescription()).isEqualTo("token refused");
                    });
                });
    }

    @ParameterizedTest(name = "{0} cannot be sent without a token: the ledger is not reached")
    @EnumSource(Rpc.class)
    void aTokenThatCannotBeObtainedIsAnOutageAndTheLedgerIsNotCalled(Rpc rpc) {
        assertThatThrownBy(() -> rpc.call.accept(adapterWith(providerRefused())))
                .isInstanceOfSatisfying(LedgerUnavailableException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("ledger unavailable");
                    assertThat(e.getCause())
                            .isInstanceOf(ServiceTokenUnavailableException.class)
                            .hasMessage("token endpoint answered HTTP 401");
                });
        assertThat(authorizations).isEmpty();
    }

    @Test
    void afterAnUnauthenticatedAnswerTheNextCallFetchesANewToken() {
        LedgerGrpcClientAdapter adapter = adapterWith(providerFetching("t1", "t2"));
        answer = Status.UNAUTHENTICATED;
        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapter))
                .isInstanceOf(LedgerUnavailableException.class);
        answer = Status.OK;

        Rpc.RELEASE_RESERVATION.call.accept(adapter);

        assertThat(authorizations).containsExactly("Bearer t1", "Bearer t2");
    }

    @Test
    void anUnauthenticatedAnswerDropsTheToken() {
        AtomicInteger dropped = new AtomicInteger();
        ServiceTokenProvider recording = new ServiceTokenProvider() {
            @Override
            public String token() {
                return "carried-token";
            }

            @Override
            public void drop() {
                dropped.incrementAndGet();
            }
        };
        answer = Status.UNAUTHENTICATED;

        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapterWith(recording)))
                .isInstanceOf(LedgerUnavailableException.class);

        assertThat(dropped).hasValue(1);
    }

    @Test
    void aPermissionDeniedAnswerKeepsTheTokenSinceAnotherOneWouldNotHelp() {
        LedgerGrpcClientAdapter adapter = adapterWith(providerFetching("t1"));
        answer = Status.PERMISSION_DENIED;
        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapter))
                .isInstanceOf(LedgerUnavailableException.class);
        answer = Status.OK;

        Rpc.RELEASE_RESERVATION.call.accept(adapter);

        assertThat(authorizations).containsExactly("Bearer t1", "Bearer t1");
    }

    @ParameterizedTest(name = "{0} is an outage that keeps the token")
    @EnumSource(
            value = Status.Code.class,
            names = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL"})
    void anyOtherStatusIsAnOutageThatKeepsTheToken(Status.Code code) {
        LedgerGrpcClientAdapter adapter = adapterWith(providerFetching("t1"));
        answer = code.toStatus();
        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapter))
                .isInstanceOf(LedgerUnavailableException.class);
        answer = Status.OK;

        Rpc.RELEASE_RESERVATION.call.accept(adapter);

        assertThat(authorizations).containsExactly("Bearer t1", "Bearer t1");
    }

    /** Everything the loggers of the JVM received, as one text per line. */
    private List<String> loggedLines() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage()
                        + " "
                        + Arrays.toString(event.getArgumentArray())
                        + " "
                        + (event.getThrowableProxy() == null
                                ? ""
                                : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .toList();
    }

    @Test
    void neitherTheSecretNorATokenIsLoggedWhateverTheOutcome() {
        LedgerGrpcClientAdapter adapter = adapterWith(providerFetching("token-one-abc", "token-two-def"));
        Rpc.RELEASE_RESERVATION.call.accept(adapter);
        answer = Status.UNAUTHENTICATED.withDescription("token refused");
        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapter))
                .isInstanceOf(LedgerUnavailableException.class);
        answer = Status.PERMISSION_DENIED.withDescription("token refused");
        assertThatThrownBy(() -> Rpc.RELEASE_RESERVATION.call.accept(adapter))
                .isInstanceOf(LedgerUnavailableException.class);
        assertThatThrownBy(() -> Rpc.POST_TRANSFER.call.accept(adapterWith(providerRefused())))
                .isInstanceOf(LedgerUnavailableException.class);

        assertThat(loggedLines())
                .isNotEmpty()
                .noneMatch(line -> line.contains(CLIENT_SECRET)
                        || line.contains("token-one-abc")
                        || line.contains("token-two-def"));
    }

    @Test
    void anErrorBodyOfTheIdentityProviderThatEchoesTheSecretIsNeverLoggedNorChained() {
        String echoed = "{\"error\":\"invalid_client\",\"client_secret\":\"" + CLIENT_SECRET
                + "\",\"access_token\":\"leaked-token-xyz\"}";

        Throwable thrown =
                catchThrowable(() -> Rpc.POST_TRANSFER.call.accept(adapterWith(providerRefusedWith(echoed))));

        assertThat(thrown).isInstanceOf(LedgerUnavailableException.class);
        List<String> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            chain.add(t + " " + Arrays.toString(t.getSuppressed()));
        }
        assertThat(chain).anyMatch(line -> line.contains("token endpoint answered HTTP 401"));
        assertThat(chain).noneMatch(line -> line.contains(CLIENT_SECRET) || line.contains("leaked-token-xyz"));
        assertThat(loggedLines())
                .isNotEmpty()
                .noneMatch(line -> line.contains(CLIENT_SECRET) || line.contains("leaked-token-xyz"));
    }

    @Test
    void aCredentialRefusalIsLoggedAsAWarningWithItsStatus() {
        answer = Status.UNAUTHENTICATED;
        assertThatThrownBy(() -> Rpc.POST_TRANSFER.call.accept(adapterWith(fixed("service-token"))));

        assertThat(appender.list)
                .filteredOn(event -> event.getLoggerName().equals(LedgerGrpcClientAdapter.class.getName()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getMessage()).isEqualTo("ledger refused the service token");
                    assertThat(event.getArgumentArray()).hasSize(1);
                    assertThat(event.getArgumentArray()[0]).hasToString("grpc.status=UNAUTHENTICATED");
                });
    }

    @Test
    void aTokenThatCannotBeObtainedIsLoggedAsAWarningWithItsCause() {
        assertThatThrownBy(() -> Rpc.POST_TRANSFER.call.accept(adapterWith(providerRefused())));

        assertThat(appender.list)
                .filteredOn(event -> event.getLoggerName().equals(SpringServiceTokenProvider.class.getName()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(event.getMessage()).isEqualTo("service token not obtained");
                    assertThat(event.getArgumentArray()).hasSize(1);
                    assertThat(event.getArgumentArray()[0]).hasToString("failure=token endpoint answered HTTP 401");
                });
    }
}
