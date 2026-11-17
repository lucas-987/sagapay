package dev.treyer.sagapay.orchestrator.security;

import com.sun.net.httpserver.HttpServer;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * A real ledger built from its Dockerfile, with its database. It checks tokens like anywhere
 * else: the keys it trusts are served by the test JVM, which stands in for the identity provider,
 * and the tests sign their tokens with the matching key ({@link TestTokens}).
 */
public final class LedgerContainer {

    private static final String KEY_SET_PATH = "/jwks";

    /** Started with the first use and kept for the JVM: every ledger container reads the same keys. */
    private static final HttpServer KEY_SET_SERVER = startKeySetServer();

    private LedgerContainer() {}

    public static PostgreSQLContainer postgres(Network network) {
        return new PostgreSQLContainer("postgres:17")
                .withNetwork(network)
                .withNetworkAliases("ledger-postgres")
                .withDatabaseName("ledger_svc")
                .withUsername("sagapay")
                .withPassword("sagapay-test");
    }

    public static GenericContainer<?> create(Network network, PostgreSQLContainer postgres) {
        return new GenericContainer<>(new ImageFromDockerfile()
                        .withFileFromPath(".", repoRoot())
                        .withDockerfilePath("ledger-accounts-service/Dockerfile")
                        .withBuildArg("MAVEN_MIRROR_URL", System.getenv().getOrDefault("MAVEN_MIRROR_URL", "")))
                .withNetwork(network)
                .dependsOn(postgres)
                .withExposedPorts(8081, 9091)
                .withEnv(Map.ofEntries(
                        // local seeds the sample accounts.
                        Map.entry("SPRING_PROFILES_ACTIVE", "local"),
                        Map.entry("SPRING_DATASOURCE_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc"),
                        Map.entry("SPRING_DATASOURCE_USERNAME", "ledger_app"),
                        Map.entry("SPRING_DATASOURCE_PASSWORD", "ledger-app-test"),
                        Map.entry("SPRING_FLYWAY_URL", "jdbc:postgresql://ledger-postgres:5432/ledger_svc"),
                        Map.entry("SPRING_FLYWAY_USER", "sagapay"),
                        Map.entry("SPRING_FLYWAY_PASSWORD", "sagapay-test"),
                        Map.entry("SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPUSERNAME", "ledger_app"),
                        Map.entry("SPRING_FLYWAY_PLACEHOLDERS_LEDGERAPPPASSWORD", "ledger-app-test"),
                        Map.entry("OAUTH2_ISSUER_URI", TestTokens.ISSUER),
                        Map.entry(
                                "OAUTH2_JWK_SET_URI",
                                "http://host.testcontainers.internal:"
                                        + KEY_SET_SERVER.getAddress().getPort() + KEY_SET_PATH)))
                .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofMinutes(5)));
    }

    /** The account of a seeded user, as a signed-in user reads it. */
    public static UUID lookupAccountId(GenericContainer<?> ledger, String handle) {
        Map<?, ?> response = RestClient.create()
                .get()
                .uri(
                        "http://%s:%d/v1/users/lookup?handle={handle}"
                                .formatted(ledger.getHost(), ledger.getMappedPort(8081)),
                        handle)
                .headers(headers -> headers.setBearerAuth(TestTokens.forSubject(UUID.randomUUID())))
                .retrieve()
                .body(Map.class);
        return UUID.fromString((String) response.get("accountId"));
    }

    /** The credential of the orchestrator on a raw gRPC channel to the container. */
    public static ClientInterceptor serviceToken() {
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + TestTokens.ledgerServiceToken());
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }

    // The ledger's Dockerfile builds from the repository root.
    private static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    private static HttpServer startKeySetServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(KEY_SET_PATH, exchange -> {
                byte[] body = TestTokens.jwkSetJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            Testcontainers.exposeHostPorts(server.getAddress().getPort());
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
