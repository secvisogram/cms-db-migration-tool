package de.bsi.secvisogram.csafmigrator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouchDbClientRetryTest {

    private static final String ONE_DOC_PAGE =
            "{\"rows\": [{\"id\": \"a\", \"doc\": {\"_id\": \"a\", \"type\": \"Counter\"}}]}";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Answers every request with {@code statuses[n]} for the n-th request, the last one repeating. */
    private void respondWith(int... statuses) {
        server.createContext("/db/_all_docs", exchange -> {
            int status = statuses[Math.min(requests.getAndIncrement(), statuses.length - 1)];
            byte[] body = (status == 200 ? ONE_DOC_PAGE : "{\"error\":\"nope\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private CouchDbClient client() {
        return new CouchDbClient("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/db",
                "u", "p", 500, Duration.ofMillis(10));
    }

    private static List<String> readAllIds(CouchDbClient client) throws Exception {
        List<String> ids = new ArrayList<>();
        client.forEachDocument(doc -> ids.add(doc.path("_id").asText()));
        return ids;
    }

    @Test
    void retriesServerErrorsUntilTheRequestSucceeds() throws Exception {
        respondWith(503, 500, 200);

        assertEquals(List.of("a"), readAllIds(client()));
        assertEquals(3, requests.get());
    }

    @Test
    void retriesRateLimiting() throws Exception {
        respondWith(429, 200);

        assertEquals(List.of("a"), readAllIds(client()));
        assertEquals(2, requests.get());
    }

    @Test
    void givesUpAfterFourAttempts() {
        respondWith(503);

        IOException e = assertThrows(IOException.class, () -> readAllIds(client()));

        assertTrue(e.getMessage().contains("4 attempts"), e.getMessage());
        assertEquals(4, requests.get());
    }

    @Test
    void doesNotRetryClientErrors() {
        respondWith(401);

        IOException e = assertThrows(IOException.class, () -> readAllIds(client()));

        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void retriesWhenTheConnectionIsRefused() throws Exception {
        CouchDbClient client = client();
        server.stop(0);

        IOException e = assertThrows(IOException.class, () -> readAllIds(client));

        assertTrue(e.getMessage().contains("4 attempts"), e.getMessage());
    }
}
