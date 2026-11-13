package dev.treyer.sagapay.orchestrator.e2e;

import dev.treyer.sagapay.orchestrator.security.LedgerContainer;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** What the end-to-end tests share: a real ledger built from its Dockerfile, and the reads and
 * fake fraud results around the orchestrator. */
final class E2ESupport {

    private E2ESupport() {}

    static PostgreSQLContainer ledgerPostgres(Network network) {
        return LedgerContainer.postgres(network);
    }

    static GenericContainer<?> ledger(Network network, PostgreSQLContainer ledgerPostgres) {
        return LedgerContainer.create(network, ledgerPostgres);
    }

    static void pointOrchestratorAt(DynamicPropertyRegistry registry, GenericContainer<?> ledger) {
        registry.add(
                "spring.grpc.client.channel.ledger.target",
                () -> "static://" + ledger.getHost() + ":" + ledger.getMappedPort(9091));
        registry.add("ledger.rest.base-url", () -> "http://" + ledger.getHost() + ":" + ledger.getMappedPort(8081));
    }

    /** The account of a seeded user, as any signed-in user reads it. */
    static UUID lookupAccountId(RestClient ledgerRest, String handle) {
        return lookupAccountId(ledgerRest, handle, TestTokens.forSubject(UUID.randomUUID()));
    }

    static UUID lookupAccountId(RestClient ledgerRest, String handle, String token) {
        Map<?, ?> response = ledgerRest
                .get()
                .uri("/v1/users/lookup?handle={handle}", handle)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) response.get("accountId"));
    }

    /** The wallet endpoint serves the token's own account, so each read signs in as that account. */
    static BigDecimal balanceOf(RestClient ledgerRest, UUID accountId) {
        return walletAmount(ledgerRest, accountId, "balance");
    }

    static BigDecimal availableOf(RestClient ledgerRest, UUID accountId) {
        return walletAmount(ledgerRest, accountId, "available");
    }

    private static BigDecimal walletAmount(RestClient ledgerRest, UUID accountId, String field) {
        Map<?, ?> response = ledgerRest
                .get()
                .uri("/v1/wallet")
                .header("Authorization", bearer(accountId))
                .retrieve()
                .body(Map.class);
        Map<?, ?> money = (Map<?, ?>) response.get(field);
        return new BigDecimal((String) money.get("amount"));
    }

    static String bearer(UUID userId) {
        return "Bearer " + TestTokens.forSubject(userId);
    }

    /** A send of EUR {@code amount} from {@code senderId} to {@code recipientId} under {@code key}. */
    static ResponseEntity<Map<String, Object>> send(
            RestClient orchestratorRest, UUID senderId, UUID recipientId, String amount, UUID key) {
        return orchestratorRest
                .post()
                .uri("/v1/transfers")
                .header("Authorization", bearer(senderId))
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"toUserId": "%s", "amount": {"currency": "EUR", "amount": "%s"}, "note": "e2e"}""".formatted(recipientId, amount))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {});
    }

    static Map<?, ?> waitForStatus(
            RestClient orchestratorRest, UUID transferId, UUID userId, String expectedStatus, Duration timeout) {
        return waitForStatus(orchestratorRest, transferId, bearer(userId), expectedStatus, timeout);
    }

    /** {@code authorization} is the whole header value. */
    static Map<?, ?> waitForStatus(
            RestClient orchestratorRest,
            UUID transferId,
            String authorization,
            String expectedStatus,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Map<?, ?> last = null;
        while (System.nanoTime() < deadline) {
            last = orchestratorRest
                    .get()
                    .uri("/v1/transfers/{id}", transferId)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(Map.class);
            if (expectedStatus.equals(last.get("status"))) {
                return last;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError(
                "transfer " + transferId + " never reached " + expectedStatus + ", last seen: " + last);
    }

    /** The fraud service's published result, built by hand: only the fields the orchestrator reads matter. */
    static UUID publishFraudResult(KafkaTemplate<String, String> kafka, UUID transferId, String decision) {
        UUID eventId = UUID.randomUUID();
        String type = "CLEARED".equals(decision) ? "TransferCleared.v1" : "TransferFlagged.v1";
        String score = "CLEARED".equals(decision) ? "0.1500" : "0.9200";
        String event = """
                {"id":"%s","source":"fraud-detection-service","type":"%s",\
                "time":"2026-11-01T10:00:00Z","subject":"%s","traceparent":null,\
                "data":{"transferId":"%s","senderId":"%s","decision":"%s","score":%s,\
                "reasons":["AMOUNT_ROUND"],"modelVersion":"v1","ts":"2026-11-01T10:00:00Z"}}""".formatted(eventId, type, transferId, transferId, UUID.randomUUID(), decision, score);
        kafka.send("payments.fraud.results", transferId.toString(), event).join();
        return eventId;
    }

    /** A result that arrives before the screening step is recorded and ignored, so the transfer must be
     * screened first. */
    static UUID publishFraudResultOnceScreening(
            RestClient orchestratorRest,
            KafkaTemplate<String, String> kafka,
            UUID transferId,
            UUID senderId,
            String decision) {
        waitForStatus(orchestratorRest, transferId, senderId, "SCREENING", Duration.ofSeconds(15));
        return publishFraudResult(kafka, transferId, decision);
    }
}
