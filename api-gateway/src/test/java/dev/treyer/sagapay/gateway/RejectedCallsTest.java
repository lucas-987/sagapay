package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RejectedCallsTest extends GatewayTestBase {

    private static final String[] ROUTED_PATHS = {
        "/v1/transfers",
        "/v1/transfers/9f0c1e2a-0000-4000-8000-000000000001",
        "/v1/wallet",
        "/v1/wallet/postings",
        "/v1/users/lookup",
        "/v1/stream/activity"
    };

    static Stream<Arguments> credentialsAndPaths() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String path : ROUTED_PATHS) {
            cases.add(Arguments.of("no token", path, (Supplier<String>) () -> null));
            cases.add(Arguments.of("an expired token", path, (Supplier<String>) TestTokens::expired));
            cases.add(Arguments.of(
                    "a token from another issuer", path, (Supplier<String>) TestTokens::fromAnotherIssuer));
            cases.add(Arguments.of(
                    "a token with a bad signature", path, (Supplier<String>) TestTokens::withABadSignature));
            cases.add(Arguments.of("a malformed token", path, (Supplier<String>) () -> "not-a-token"));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("credentialsAndPaths")
    @Tag("M4-S02")
    @DisplayName("A call without a valid token never reaches a service")
    void aCallWithoutAValidTokenNeverReachesAService(String credential, String path, Supplier<String> token)
            throws Exception {
        HttpResponse<String> response = get(path, token.get());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(TRANSFERS.received()).isEmpty();
        assertThat(LEDGER.received()).isEmpty();
    }
}
