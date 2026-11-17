package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.http.HttpClient;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "activity.stream.timeout-ms=1500")
class ActivityStreamTimeoutTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("A stream ends when its configured timeout expires")
    void aStreamEndsWhenItsTimeoutExpires() throws Exception {
        try (ActivityStreamSupport.Stream stream =
                new ActivityStreamSupport.Stream(HttpClient.newHttpClient(), port, UUID.randomUUID())) {
            assertThat(stream.status()).isEqualTo(200);

            await().atMost(ActivityStreamSupport.PATIENCE).until(stream::ended);
        }
    }
}
