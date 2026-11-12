package dev.treyer.sagapay.gateway.support;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A downstream service in the test JVM: it records each call it receives and answers with its own
 * name, unless the test installs another answer.
 */
public final class StubService implements AutoCloseable {

    public record Received(String method, String uri, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    @FunctionalInterface
    public interface Answer {
        Publisher<Void> to(HttpServerRequest request, HttpServerResponse response);
    }

    private final String name;
    private final DisposableServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Answer answer;

    private StubService(String name) {
        this.name = name;
        this.answer = this::nameOnly;
        this.server = HttpServer.create()
                .host("localhost")
                .port(0)
                .handle((request, response) -> request.receive()
                        .aggregate()
                        .asString()
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            received.add(
                                    new Received(request.method().name(), request.uri(), headersOf(request), body));
                            return Mono.from(answer.to(request, response));
                        }))
                .bindNow();
    }

    public static StubService named(String name) {
        return new StubService(name);
    }

    public String name() {
        return name;
    }

    public String uri() {
        return "http://localhost:" + server.port();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void answerWith(Answer answer) {
        this.answer = answer;
    }

    public void reset() {
        received.clear();
        answer = this::nameOnly;
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    private Publisher<Void> nameOnly(HttpServerRequest request, HttpServerResponse response) {
        return response.header("X-Served-By", name).sendString(Mono.just(name));
    }

    private static Map<String, String> headersOf(HttpServerRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.requestHeaders()
                .forEach(entry -> headers.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        return headers;
    }
}
