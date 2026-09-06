package io.prreviewassistant.github.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class GitHubMockServer implements AutoCloseable {

    private final HttpServer server;
    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    GitHubMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void enqueue(int status, String body) {
        enqueue(status, body, Map.of());
    }

    void enqueue(int status, String body, Map<String, String> headers) {
        responses.add(new Response(status, body, headers, null, null));
    }

    void enqueueBlocked(CountDownLatch entered, CountDownLatch release) {
        responses.add(new Response(200, "{}", Map.of(), entered, release));
    }

    RecordedRequest onlyRequest() {
        if (requests.size() != 1) {
            throw new AssertionError("Expected one request but received " + requests.size());
        }
        return requests.getFirst();
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                exchange.getRequestHeaders()));
        Response response = responses.poll();
        if (response == null) {
            response = new Response(500, "{}", Map.of(), null, null);
        }
        if (response.entered() != null) {
            response.entered().countDown();
            try {
                response.release().await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record RecordedRequest(String method, URI uri, com.sun.net.httpserver.Headers headers) {
    }

    private record Response(
            int status,
            String body,
            Map<String, String> headers,
            CountDownLatch entered,
            CountDownLatch release) {
    }
}
