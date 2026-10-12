package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal read-only client for a single CouchDB database, using nothing but the JDK's built-in
 * HttpClient and CouchDB's plain HTTP API, so the tool needs no CouchDB client library.
 */
public class CouchDbClient {

    public static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI dbBaseUri;
    private final String authHeader;
    private final int pageSize;
    private final Duration initialRetryDelay;

    public CouchDbClient(String baseUrl, String user, String password) {
        this(baseUrl, user, password, DEFAULT_PAGE_SIZE);
    }

    public CouchDbClient(String baseUrl, String user, String password, int pageSize) {
        this(baseUrl, user, password, pageSize, INITIAL_RETRY_DELAY);
    }

    CouchDbClient(String baseUrl, String user, String password, int pageSize, Duration initialRetryDelay) {
        this.pageSize = pageSize;
        this.initialRetryDelay = initialRetryDelay;
        this.dbBaseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String credentials = user + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** Receives one page of documents at a time. */
    @FunctionalInterface
    public interface PageHandler {
        void accept(List<JsonNode> page) throws Exception;
    }

    /** Calls {@code handler} for every document, one page at a time. See {@link #forEachPage}. */
    public void forEachDocument(Consumer<JsonNode> handler) throws Exception {
        forEachPage(page -> page.forEach(handler));
    }

    /**
     * Walks the whole database via {@code _all_docs?include_docs=true}, skipping CouchDB design
     * documents ({@code _design/*}), and hands each page to {@code handler}. Only one page is held
     * in memory at a time. Paging is keyset-based ({@code startkey} = first id of the next page), so
     * it does not slow down on large databases the way {@code skip} does.
     */
    public void forEachPage(PageHandler handler) throws Exception {
        String startKey = null;
        while (true) {
            // One row more than a page: the extra row is where the next page starts.
            JsonNode rows = fetchPage(startKey, pageSize + 1).path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                return;
            }
            boolean hasNext = rows.size() > pageSize;
            int rowsInPage = hasNext ? pageSize : rows.size();
            List<JsonNode> page = new ArrayList<>(rowsInPage);
            for (int i = 0; i < rowsInPage; i++) {
                JsonNode row = rows.get(i);
                if (row.path("id").asText("").startsWith("_design/")) {
                    continue;
                }
                JsonNode doc = row.path("doc");
                if (doc.isMissingNode() || doc.isNull()) {
                    continue;
                }
                page.add(doc);
            }
            if (!page.isEmpty()) {
                handler.accept(page);
            }
            if (!hasNext) {
                return;
            }
            startKey = rows.get(pageSize).path("id").asText();
        }
    }

    private JsonNode fetchPage(String startKey, int limit) throws IOException, InterruptedException {
        String startKeyParam = startKey == null ? ""
                : "&startkey=" + URLEncoder.encode(objectMapper.writeValueAsString(startKey), StandardCharsets.UTF_8);
        URI uri = URI.create(dbBaseUri + "/_all_docs?include_docs=true&limit=" + limit + startKeyParam);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        // A page fetch is a read-only GET, so repeating it is safe. Only transient failures are
        // retried (connection problems, timeouts, 408/429/5xx); anything else, such as a 401 or
        // 404, fails immediately.
        for (int attempt = 1; ; attempt++) {
            try {
                return fetchOnce(request);
            } catch (TransientFailure e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IOException("CouchDB request failed after " + MAX_ATTEMPTS + " attempts: "
                            + e.getMessage(), e);
                }
                long delayMillis = initialRetryDelay.toMillis() << (attempt - 1);
                System.err.println("CouchDB request failed (" + e.getMessage() + "), retrying in "
                        + delayMillis + " ms (attempt " + attempt + " of " + MAX_ATTEMPTS + ") ...");
                Thread.sleep(delayMillis);
            }
        }
    }

    private JsonNode fetchOnce(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new TransientFailure(e.toString(), e);
        }
        // Parse straight from the stream instead of first reading the whole body into a String.
        // The page is fully parsed here, before anything reaches the handler, so a retry after a
        // failure part-way through the body can never deliver a document twice.
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 408 || status == 429 || status >= 500) {
                throw new TransientFailure("status " + status + " for " + request.uri(), null);
            }
            if (status != 200) {
                throw new IOException("CouchDB request failed with status " + status + " for " + request.uri()
                        + ": " + new String(body.readNBytes(4096), StandardCharsets.UTF_8));
            }
            return parse(body);
        }
    }

    /** A body that is cut off or whose connection drops is transient; a malformed one is not. */
    private JsonNode parse(InputStream body) throws IOException {
        try {
            return objectMapper.readTree(body);
        } catch (JsonEOFException e) {
            throw new TransientFailure("response was cut off: " + e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            throw new TransientFailure(e.toString(), e);
        }
    }

    /** A failure worth retrying. */
    private static final class TransientFailure extends IOException {
        TransientFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
