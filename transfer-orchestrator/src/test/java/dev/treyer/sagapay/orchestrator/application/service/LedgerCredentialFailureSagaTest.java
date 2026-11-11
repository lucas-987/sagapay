package dev.treyer.sagapay.orchestrator.application.service;

import com.google.protobuf.Timestamp;
import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.v1.Empty;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveRequest;
import dev.treyer.sagapay.ledger.v1.CheckAndReserveResponse;
import dev.treyer.sagapay.ledger.v1.LedgerServiceGrpc;
import dev.treyer.sagapay.ledger.v1.PostTransferRequest;
import dev.treyer.sagapay.ledger.v1.PostTransferResponse;
import dev.treyer.sagapay.ledger.v1.ReleaseReservationRequest;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.in.scheduler.SagaReprisePoller;
import dev.treyer.sagapay.orchestrator.adapter.out.grpc.ServiceTokenProvider;
import dev.treyer.sagapay.orchestrator.adapter.out.grpc.ServiceTokenUnavailableException;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.OutboxRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.SagaStepRepository;
import dev.treyer.sagapay.orchestrator.adapter.out.persistence.TransferRepository;
import dev.treyer.sagapay.orchestrator.domain.FraudDecision;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.OutboxRow;
import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real ledger client and the real saga, against an in-process ledger and an identity provider
 * that the test breaks and repairs: a credential problem leaves the transfer where it was, and the
 * recovery poller finishes it once the credential works.
 */
@Import({TestcontainersConfiguration.class, LedgerCredentialFailureSagaTest.CredentialConfig.class})
@SpringBootTest(
        properties = {
            "saga.deadline.sweep-interval-ms=3600000",
            "saga.reprise.sweep-interval-ms=3600000",
            "saga.reprise.grace-period-ms=0"
        })
class LedgerCredentialFailureSagaTest {

    /** How the credential is broken: no token from the identity provider, or a refusal of the ledger. */
    enum Breakage {
        TOKEN_NOT_OBTAINED,
        UNAUTHENTICATED,
        PERMISSION_DENIED
    }

    private static final String SERVICE_TOKEN = "Bearer service-token";

    @Autowired
    private SagaService sagaService;

    @Autowired
    private SagaReprisePoller reprisePoller;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private SagaTransitionWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private CredentialConfig.Credential credential;

    @Autowired
    private CredentialConfig.RecordingLedger ledger;

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from saga_steps");
        jdbcTemplate.update("delete from transfers");
        circuitBreakerRegistry.circuitBreaker("ledger").reset();
        credential.works();
        ledger.clear();
    }

    private Transfer initiate() {
        return sagaService
                .initiateTransfer(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Money.of("80.00", "EUR"),
                        UUID.randomUUID(),
                        null)
                .transfer();
    }

    private Transfer reload(Transfer transfer) {
        return transferRepository.findById(transfer.getId()).orElseThrow();
    }

    private List<String> eventTypes(Transfer transfer) {
        return outboxRepository.findByAggregateIdOrderByCreatedAt(transfer.getId()).stream()
                .map(OutboxRow::getEventType)
                .toList();
    }

    private List<String> steps(Transfer transfer) {
        return sagaStepRepository.findByTransferIdOrderByAtAscIdAsc(transfer.getId()).stream()
                .map(step -> step.getStep() + " " + step.getOutcome())
                .toList();
    }

    @ParameterizedTest(name = "{0} on the reservation: the transfer waits, then recovery reserves it")
    @EnumSource(Breakage.class)
    @DisplayName("A credential failure on the reservation leaves the transfer for recovery")
    void aCredentialFailureOnTheReservationLeavesTheTransferForRecovery(Breakage breakage) {
        Transfer transfer = initiate();
        credential.breaks(breakage);

        assertThatThrownBy(() -> sagaService.advance(transfer.getId())).isInstanceOf(LedgerUnavailableException.class);

        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(eventTypes(transfer)).containsExactly("TransferInitiated");
        assertThat(ledger.reservations()).isEmpty();

        credential.works();
        circuitBreakerRegistry.circuitBreaker("ledger").reset();
        reprisePoller.sweep();

        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.SCREENING);
        assertThat(eventTypes(transfer)).containsExactly("TransferInitiated", "FundsReserved");
        assertThat(ledger.reservations()).hasSize(1);
        assertThat(ledger.authorizationsOfAcceptedCalls()).containsOnly(SERVICE_TOKEN);
    }

    @ParameterizedTest(
            name = "{0} on the posting: the transfer stays cleared with its money held, then recovery posts it")
    @EnumSource(Breakage.class)
    @DisplayName("A credential failure on the posting keeps the reservation until recovery posts")
    void aCredentialFailureOnThePostingKeepsTheReservationUntilRecoveryPosts(Breakage breakage) {
        Transfer transfer = initiate();
        sagaService.advance(transfer.getId());
        credential.breaks(breakage);

        sagaService.applyFraudResult(new FraudResult(
                UUID.randomUUID(), transfer.getId(), FraudDecision.CLEARED, new BigDecimal("0.1500"), List.of()));

        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.CLEARED);
        assertThat(ledger.posts()).isEmpty();
        assertThat(ledger.releases()).isEmpty();

        credential.works();
        circuitBreakerRegistry.circuitBreaker("ledger").reset();
        reprisePoller.sweep();

        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(ledger.posts()).hasSize(1);
        assertThat(ledger.releases()).isEmpty();
    }

    @ParameterizedTest(
            name = "{0} on the sweeper's release: the release stays pending, then recovery releases the funds")
    @EnumSource(Breakage.class)
    @DisplayName("A credential failure on the sweeper's release stays pending until recovery releases")
    void aCredentialFailureOnTheSweepersReleaseStaysPendingUntilRecoveryReleases(Breakage breakage) {
        Transfer transfer = initiate();
        sagaService.advance(transfer.getId());
        jdbcTemplate.update("""
                update saga_steps set deadline = now() - make_interval(secs => 1)
                where transfer_id = ? and step = 'SCREEN'
                """, transfer.getId());
        credential.breaks(breakage);

        sagaService.sweepOverdueTransfers();

        assertThat(reload(transfer).getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(steps(transfer)).last().isEqualTo("RELEASE RETRY");
        assertThat(ledger.releases()).isEmpty();

        credential.works();
        circuitBreakerRegistry.circuitBreaker("ledger").reset();
        reprisePoller.sweep();

        assertThat(steps(transfer)).last().isEqualTo("RELEASE COMPENSATED");
        assertThat(ledger.releases()).hasSize(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CredentialConfig {

        /** What the identity provider and the ledger do about the credential, set by the test. */
        static final class Credential implements ServiceTokenProvider {

            private volatile Breakage breakage;

            void breaks(Breakage breakage) {
                this.breakage = breakage;
            }

            void works() {
                this.breakage = null;
            }

            Breakage breakage() {
                return breakage;
            }

            @Override
            public String token() {
                if (breakage == Breakage.TOKEN_NOT_OBTAINED) {
                    throw new ServiceTokenUnavailableException("token endpoint unreachable");
                }
                return "service-token";
            }

            @Override
            public void drop() {}
        }

        /** An in-process ledger that records what reaches its handlers, and refuses as the test says. */
        static final class RecordingLedger {

            private final List<String> reservations = new CopyOnWriteArrayList<>();
            private final List<String> posts = new CopyOnWriteArrayList<>();
            private final List<String> releases = new CopyOnWriteArrayList<>();
            private final List<String> authorizations = new CopyOnWriteArrayList<>();

            void clear() {
                reservations.clear();
                posts.clear();
                releases.clear();
                authorizations.clear();
            }

            List<String> reservations() {
                return reservations;
            }

            List<String> posts() {
                return posts;
            }

            List<String> releases() {
                return releases;
            }

            List<String> authorizationsOfAcceptedCalls() {
                return new ArrayList<>(authorizations);
            }
        }

        @Bean
        @Primary
        Credential credential() {
            return new Credential();
        }

        @Bean
        RecordingLedger recordingLedger() {
            return new RecordingLedger();
        }

        @Bean(destroyMethod = "shutdownNow")
        Server inProcessLedger(RecordingLedger recording, Credential credential) throws Exception {
            ServerInterceptor refuse = new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    Breakage breakage = credential.breakage();
                    if (breakage == Breakage.UNAUTHENTICATED || breakage == Breakage.PERMISSION_DENIED) {
                        call.close(Status.fromCode(Status.Code.valueOf(breakage.name())), new Metadata());
                        return new ServerCall.Listener<>() {};
                    }
                    recording.authorizations.add(
                            headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
                    return next.startCall(call, headers);
                }
            };
            return InProcessServerBuilder.forName("credential-saga-ledger")
                    .directExecutor()
                    .addService(ServerInterceptors.intercept(
                            new LedgerServiceGrpc.LedgerServiceImplBase() {
                                @Override
                                public void checkAndReserve(
                                        CheckAndReserveRequest request,
                                        StreamObserver<CheckAndReserveResponse> responseObserver) {
                                    recording.reservations.add(request.getTransferId());
                                    responseObserver.onNext(CheckAndReserveResponse.newBuilder()
                                            .setStatus(CheckAndReserveResponse.Status.OK)
                                            .setReservationId(UUID.randomUUID().toString())
                                            .setExpiresAt(Timestamp.newBuilder()
                                                    .setSeconds(Instant.now().getEpochSecond() + 300))
                                            .build());
                                    responseObserver.onCompleted();
                                }

                                @Override
                                public void postTransfer(
                                        PostTransferRequest request,
                                        StreamObserver<PostTransferResponse> responseObserver) {
                                    recording.posts.add(request.getTransferId());
                                    responseObserver.onNext(PostTransferResponse.getDefaultInstance());
                                    responseObserver.onCompleted();
                                }

                                @Override
                                public void releaseReservation(
                                        ReleaseReservationRequest request, StreamObserver<Empty> responseObserver) {
                                    recording.releases.add(request.getTransferId());
                                    responseObserver.onNext(Empty.getDefaultInstance());
                                    responseObserver.onCompleted();
                                }
                            },
                            refuse))
                    .build()
                    .start();
        }

        @Bean(destroyMethod = "shutdownNow")
        @Primary
        ManagedChannel inProcessLedgerChannel(Server inProcessLedger) {
            return InProcessChannelBuilder.forName("credential-saga-ledger")
                    .directExecutor()
                    .build();
        }
    }
}
