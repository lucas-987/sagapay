package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.StubService;
import dev.treyer.sagapay.gateway.support.TestIdentityProvider;
import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * The gateway on a real port, in front of two stub services, checking tokens against a local key
 * endpoint. The response timeout is short so that a test can outlast it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.gateway.server.webflux.httpclient.response-timeout=" + GatewayTestBase.RESPONSE_TIMEOUT_MILLIS
                    + "ms"
        })
abstract class GatewayTestBase {

    /** Every test that outlasts the response timeout derives its wait from this value. */
    static final int RESPONSE_TIMEOUT_MILLIS = 1000;

    // Started once per JVM and never stopped: the contexts of the subclasses are cached and keep
    // pointing at these addresses.
    static final StubService TRANSFERS = StubService.named("transfer service");
    static final StubService LEDGER = StubService.named("ledger");

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("ORCHESTRATOR_URI", TRANSFERS::uri);
        registry.add("LEDGER_URI", LEDGER::uri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestIdentityProvider::jwkSetUri);
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void resetStubs() {
        TRANSFERS.reset();
        LEDGER.reset();
    }

    HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        return send(requestFor(path, token).GET().build());
    }

    HttpRequest.Builder requestFor(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }

    static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
