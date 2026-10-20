package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimal read-only client for a single CouchDB database, using nothing but the JDK's built-in
 * HttpClient and CouchDB's plain HTTP API, so the tool needs no CouchDB client library.
 */
public class CouchDbClient {

    public static final int DEFAULT_PAGE_SIZE = 500;
    /** Documents with large csaf bodies are fetched in small chunks to bound the memory per request. */
    static final int BODY_HEAVY_CHUNK = 50;
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final double SLOW_REQUEST_SECONDS = 30;

    /** The fields the validation pass needs, so it never has to download the large csaf documents. */
    static final List<String> PROJECTED_FIELDS = List.of("_id", "type", "workflowState", "owner", "versioningType",
            "advisoryReference", "commentText", "advisoryId", "answerTo", "createdAt", "user", "changeType",
            "commentId", "count", "csaf.document.tracking.id", "csaf.document.title");

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

    /** Receives one document at a time and may fail. */
    @FunctionalInterface
    public interface DocumentHandler {
        void accept(JsonNode doc) throws Exception;
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

    /**
     * Calls {@code handler} for every document, but with only the small fields the validation needs
     * (see {@link #PROJECTED_FIELDS}) instead of the whole document. A large CSAF advisory can be tens of
     * megabytes, and downloading and parsing them dominates the run time; this is many times faster. Uses
     * Mango's {@code POST _find} with bookmark paging.
     */
    public void forEachProjectedDocument(DocumentHandler handler) throws Exception {
        String bookmark = null;
        while (true) {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("selector").putObject("_id").putNull("$gt");
            ArrayNode fields = body.putArray("fields");
            PROJECTED_FIELDS.forEach(fields::add);
            JsonNode result = find(body, bookmark);
            JsonNode docs = result.path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                return;
            }
            for (JsonNode doc : docs) {
                if (!doc.path("_id").asText("").startsWith("_design/")) {
                    handler.accept(doc);
                }
            }
            bookmark = result.path("bookmark").asText(null);
        }
    }

    /**
     * Of the given Advisory or AdvisoryVersion ids, those whose {@code csaf} field is a JSON object. A
     * projection cannot tell a missing or non-object {@code csaf} from an object that has neither a tracking
     * id nor a title, so for those few documents the answer is fetched directly, by id.
     */
    public Set<String> findIdsWithCsafObject(List<String> ids) throws Exception {
        Set<String> withCsafObject = new HashSet<>();
        forEachByIds(ids, BODY_HEAVY_CHUNK, page -> page.forEach(doc -> {
            if (doc.path("csaf").isObject()) {
                withCsafObject.add(doc.path("_id").asText());
            }
        }));
        return withCsafObject;
    }

    /**
     * Fetches exactly the documents with the given ids, {@code chunkSize} at a time, through
     * {@code POST _all_docs?include_docs=true} with a {@code keys} list. This reads the by-id index
     * directly: nothing is scanned, no index is needed and nothing is written to the database. An id
     * without a document (never there, or deleted since) is skipped, so the caller notices it as a
     * missing document in its counts.
     *
     * <p>Pass the ids sorted, so that documents that lie next to each other in the database are
     * fetched together.</p>
     */
    public void forEachByIds(List<String> ids, int chunkSize, PageHandler handler) throws Exception {
        for (int from = 0; from < ids.size(); from += chunkSize) {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode keys = body.putArray("keys");
            ids.subList(from, Math.min(from + chunkSize, ids.size())).forEach(keys::add);
            HttpRequest request = HttpRequest.newBuilder(URI.create(dbBaseUri + "/_all_docs?include_docs=true"))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            List<JsonNode> docs = new ArrayList<>();
            for (JsonNode row : sendWithRetry(request).path("rows")) {
                JsonNode doc = row.path("doc");
                if (!doc.isMissingNode() && !doc.isNull()) {
                    docs.add(doc);
                }
            }
            if (!docs.isEmpty()) {
                handler.accept(docs);
            }
        }
    }

    /** The number of documents in the database (design documents included), from the database info. */
    public long documentCount() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(dbBaseUri)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return sendWithRetry(request).path("doc_count").asLong(0);
    }

    private JsonNode find(ObjectNode body, String bookmark) throws IOException, InterruptedException {
        body.put("limit", pageSize);
        if (bookmark != null) {
            body.put("bookmark", bookmark);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(dbBaseUri + "/_find"))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return sendWithRetry(request);
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
        return sendWithRetry(request);
    }

    /**
     * Sends a read-only request (a GET, or a {@code _find} query, which only reads), so repeating it is
     * safe. Only transient failures are retried (connection problems, timeouts, 408/429/5xx); anything
     * else, such as a 401 or 404, fails immediately.
     */
    private JsonNode sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                long startedAt = System.nanoTime();
                JsonNode result = fetchOnce(request);
                double seconds = (System.nanoTime() - startedAt) / 1e9;
                if (seconds >= SLOW_REQUEST_SECONDS) {
                    System.out.printf(java.util.Locale.ROOT, "  CouchDB is slow: one request took %.0f s.%n", seconds);
                }
                return result;
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
                throw new HttpStatusException(status, "CouchDB request failed with status " + status + " for "
                        + request.uri() + ": " + new String(body.readNBytes(4096), StandardCharsets.UTF_8));
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

    /** A response with a status that is neither success nor worth retrying. */
    public static final class HttpStatusException extends IOException {
        private final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** A failure worth retrying. */
    private static final class TransientFailure extends IOException {
        TransientFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
