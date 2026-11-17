package dev.treyer.sagapay.ledger.security;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** A local HTTP server standing in for the identity provider's key endpoint, counting its reads. */
public final class KeySetServer {

    private final AtomicInteger reads = new AtomicInteger();
    private final HttpServer server;

    public KeySetServer() {
        this(Duration.ZERO);
    }

    /** A server that waits {@code delay} before answering each read. */
    public KeySetServer(Duration delay) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                reads.incrementAndGet();
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
                byte[] body = TestTokens.jwkSetJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/jwks";
    }

    public int reads() {
        return reads.get();
    }

    public void stop() {
        server.stop(0);
    }
}
