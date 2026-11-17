package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The pauses below outlast the response timeout of this test's context. */
class ActivityStreamTest extends GatewayTestBase {

    private static final Duration PAUSE_LONGER_THAN_THE_RESPONSE_TIMEOUT =
            Duration.ofMillis(RESPONSE_TIMEOUT_MILLIS * 3 / 2);

    @Test
    void eachEventReachesTheClientAsItIsSentAndAfterAnyResponseTimeoutOfTheOtherRoutes() throws Exception {
        Sinks.Many<String> laterEvents = Sinks.many().unicast().onBackpressureBuffer();
        // The service answers after a pause longer than the timeout: only the route's own setting
        // keeps the gateway from ending the call with a gateway timeout.
        TRANSFERS.answerWith((request, response) -> Mono.delay(PAUSE_LONGER_THAN_THE_RESPONSE_TIMEOUT)
                .then(Mono.from(response.header("Content-Type", "text/event-stream")
                        .sendString(Flux.concat(Mono.just("data: first\n\n"), laterEvents.asFlux())))));
        String token = TestTokens.valid();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        CompletableFuture<HttpResponse<Stream<String>>> call = HttpClient.newHttpClient()
                .sendAsync(requestFor("/v1/stream/activity", token).GET().build(), HttpResponse.BodyHandlers.ofLines());
        CompletableFuture<Void> reading = call.thenAccept(
                response -> response.body().filter(line -> !line.isEmpty()).forEach(received::add));

        assertThat(received.poll(10, TimeUnit.SECONDS)).isEqualTo("data: first");
        assertThat(call.get().statusCode()).isEqualTo(200);
        assertThat(call.get().headers().firstValue("Content-Type")).contains("text/event-stream");
        assertThat(reading).isNotDone();
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();

        Thread.sleep(PAUSE_LONGER_THAN_THE_RESPONSE_TIMEOUT);
        laterEvents.tryEmitNext("data: second\n\n");
        assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("data: second");
        assertThat(reading).isNotDone();

        laterEvents.tryEmitComplete();
        reading.get(5, TimeUnit.SECONDS);
        assertThat(TRANSFERS.received()).singleElement().satisfies(stub -> {
            assertThat(stub.uri()).isEqualTo("/v1/stream/activity");
            assertThat(stub.header("Authorization")).isEqualTo("Bearer " + token);
        });
    }
}
