package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.application.port.out.LedgerPort;
import dev.treyer.sagapay.orchestrator.domain.LedgerRejectedException;
import dev.treyer.sagapay.orchestrator.domain.LedgerUnavailableException;
import dev.treyer.sagapay.orchestrator.domain.ReservationResult;
import dev.treyer.sagapay.orchestrator.security.LedgerContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Needs the Spring-proxied bean: {@code @CircuitBreaker} is applied through AOP.
 * Ordered because the last test stops the shared ledger container for good.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import({TestcontainersConfiguration.class, FixedServiceTokenProvider.Configuration.class})
@SpringBootTest(properties = "ledger.grpc.deadline-ms=5000")
class LedgerCircuitBreakerTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer LEDGER_POSTGRES = LedgerContainer.postgres(NETWORK);

    @Container
    private static final GenericContainer<?> LEDGER = LedgerContainer.create(NETWORK, LEDGER_POSTGRES);

    private static UUID bobAccountId;

    @DynamicPropertySource
    static void ledgerGrpcTarget(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.grpc.client.channel.ledger.target",
                () -> "static://" + LEDGER.getHost() + ":" + LEDGER.getMappedPort(9091));
    }

    @BeforeAll
    static void resolveBobAccountId() {
        bobAccountId = LedgerContainer.lookupAccountId(LEDGER, "bob");
    }

    @Autowired
    private LedgerPort ledgerPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    @Order(1)
    void ledgerRejectionsSurfaceAsIsAndNeverOpenTheCircuit() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ledger");
        // More than the breaker's minimum number of calls.
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> ledgerPort.postTransfer(
                            UUID.randomUUID().toString(), bobAccountId, bobAccountId, Money.of("1.00", "EUR")))
                    .isInstanceOf(LedgerRejectedException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @Order(2)
    void killingTheLedgerContainerOpensTheCircuitAndFallsBackToLedgerUnavailable() {
        ReservationResult before = ledgerPort.checkAndReserve(
                UUID.randomUUID().toString(), bobAccountId, bobAccountId, Money.of("1.00", "EUR"));
        assertThat(before).isInstanceOf(ReservationResult.Ok.class);

        LEDGER.stop();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("ledger");
        for (int i = 0; i < 5 && circuitBreaker.getState() != CircuitBreaker.State.OPEN; i++) {
            assertThatThrownBy(() -> ledgerPort.checkAndReserve(
                            UUID.randomUUID().toString(), bobAccountId, bobAccountId, Money.of("1.00", "EUR")))
                    .isInstanceOf(LedgerUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> ledgerPort.checkAndReserve(
                        UUID.randomUUID().toString(), bobAccountId, bobAccountId, Money.of("1.00", "EUR")))
                .isInstanceOf(LedgerUnavailableException.class);
        assertThatThrownBy(() -> ledgerPort.releaseReservation(UUID.randomUUID().toString(), UUID.randomUUID()))
                .isInstanceOf(LedgerUnavailableException.class);
    }
}
