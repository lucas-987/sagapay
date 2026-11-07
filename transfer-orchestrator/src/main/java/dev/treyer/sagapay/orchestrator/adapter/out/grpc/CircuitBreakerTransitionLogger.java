package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
class CircuitBreakerTransitionLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);

    CircuitBreakerTransitionLogger(CircuitBreakerRegistry registry) {
        registry.getAllCircuitBreakers().forEach(this::logTransitions);
        registry.getEventPublisher().onEntryAdded(event -> logTransitions(event.getAddedEntry()));
    }

    private void logTransitions(CircuitBreaker circuitBreaker) {
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "circuit breaker state changed",
                        kv("circuit_breaker.name", event.getCircuitBreakerName()),
                        kv(
                                "circuit_breaker.state.from",
                                event.getStateTransition().getFromState()),
                        kv(
                                "circuit_breaker.state.to",
                                event.getStateTransition().getToState())));
    }
}
