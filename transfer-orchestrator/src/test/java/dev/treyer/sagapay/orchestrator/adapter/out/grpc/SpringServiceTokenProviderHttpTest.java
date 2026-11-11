package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import com.sun.net.httpserver.HttpServer;
import dev.treyer.sagapay.orchestrator.config.LedgerAuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production constructor, over a real socket: the wire format and the request timeout. */
class SpringServiceTokenProviderHttpTest {

    private final CountDownLatch releaseHandlers = new CountDownLatch(1);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        releaseHandlers.countDown();
        server.stop(0);
    }

    private URI start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", handler);
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/token");
    }

    @Test
    void theGrantIsPostedAsAFormAndTheTokenIsRead() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        URI tokenUri = start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"access_token\":\"t1\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        SpringServiceTokenProvider provider = new SpringServiceTokenProvider(
                new LedgerAuthProperties("transfer-orchestrator", "s3cret", tokenUri, 30_000, 2000), Clock.systemUTC());

        assertThat(provider.token()).isEqualTo("t1");

        assertThat(method).hasValue("POST");
        assertThat(contentType).hasValue("application/x-www-form-urlencoded");
        assertThat(authorization).hasValue("Basic "
                + Base64.getEncoder().encodeToString("transfer-orchestrator:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(body).hasValue("grant_type=client_credentials");
    }

    @Test
    void anIdentityProviderThatDoesNotAnswerIsAnUnavailableTokenOnceTheTimeoutPassed() throws IOException {
        URI tokenUri = start(exchange -> {
            try {
                releaseHandlers.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        SpringServiceTokenProvider provider = new SpringServiceTokenProvider(
                new LedgerAuthProperties("transfer-orchestrator", "s3cret", tokenUri, 30_000, 200), Clock.systemUTC());

        assertThatThrownBy(provider::token)
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint unreachable");
    }
}
