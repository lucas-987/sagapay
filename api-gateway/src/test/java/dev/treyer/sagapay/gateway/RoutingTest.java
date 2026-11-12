package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.StubService;
import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingTest extends GatewayTestBase {

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("/v1/transfers", TRANSFERS),
                Arguments.of("/v1/transfers/9f0c1e2a-0000-4000-8000-000000000001", TRANSFERS),
                Arguments.of("/v1/transfers/9f0c1e2a-0000-4000-8000-000000000001/confirm", TRANSFERS),
                Arguments.of("/v1/wallet", LEDGER),
                Arguments.of("/v1/wallet/postings?limit=5", LEDGER),
                Arguments.of("/v1/users/lookup?email=maya@example.com", LEDGER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    @Tag("M4-S01")
    @DisplayName("A signed-in user reaches the service behind each route")
    void aSignedInUserReachesTheServiceBehindEachRoute(String path, StubService service) throws Exception {
        HttpResponse<String> response = get(path, TestTokens.valid());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(service.name());
        assertThat(service.received()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.uri()).isEqualTo(path);
        });
        assertThat(otherThan(service).received()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void aRoutedCallArrivesWithTheSameAuthorizationHeader(String path, StubService service) throws Exception {
        String token = TestTokens.valid();

        get(path, token);

        assertThat(service.received())
                .singleElement()
                .extracting(call -> call.header("Authorization"))
                .isEqualTo("Bearer " + token);
    }

    @Test
    void aPostReachesTheServiceWithItsBodyAndItsOwnHeaders() throws Exception {
        String body = "{\"toUserId\":\"u-2\",\"amount\":{\"currency\":\"EUR\",\"amount\":\"80.00\"}}";
        HttpRequest request = requestFor("/v1/transfers", TestTokens.valid())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "6f1f6f0e-5c55-4f0a-9d6b-7b0b7f3c1a10")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = send(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(TRANSFERS.received()).singleElement().satisfies(call -> {
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.uri()).isEqualTo("/v1/transfers");
            assertThat(call.body()).isEqualTo(body);
            assertThat(call.header("Idempotency-Key")).isEqualTo("6f1f6f0e-5c55-4f0a-9d6b-7b0b7f3c1a10");
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/v1/unknown", "/v1/transfer", "/v1/stream/other", "/v1/wallets"})
    void aPathWithNoRouteAnswersNotFoundAndReachesNoService(String path) throws Exception {
        HttpResponse<String> response = get(path, TestTokens.valid());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(TRANSFERS.received()).isEmpty();
        assertThat(LEDGER.received()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/other", "/internal/v1/transfers"})
    void aPathOutsideTheApiIsRefusedToASignedInUser(String path) throws Exception {
        assertThat(get(path, TestTokens.valid()).statusCode()).isEqualTo(403);
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(TRANSFERS.received()).isEmpty();
        assertThat(LEDGER.received()).isEmpty();
    }

    @Test
    void aSlowServiceIsAnsweredWithAGatewayTimeoutOnTheOtherRoutes() throws Exception {
        TRANSFERS.answerWith(
                (request, response) -> response.sendString(Mono.just("late").delayElement(Duration.ofSeconds(3))));

        HttpResponse<String> response = get("/v1/transfers", TestTokens.valid());

        assertThat(response.statusCode()).isEqualTo(504);
    }

    private static StubService otherThan(StubService service) {
        return service == TRANSFERS ? LEDGER : TRANSFERS;
    }
}
