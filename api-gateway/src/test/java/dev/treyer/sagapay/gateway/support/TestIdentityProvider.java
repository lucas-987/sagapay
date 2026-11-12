package dev.treyer.sagapay.gateway.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A local HTTP server standing in for the identity provider's key endpoint, started once per JVM. */
public final class TestIdentityProvider {

    private static final HttpServer SERVER = start();

    private TestIdentityProvider() {}

    public static String jwkSetUri() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/jwks";
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = TestTokens.jwkSetJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
