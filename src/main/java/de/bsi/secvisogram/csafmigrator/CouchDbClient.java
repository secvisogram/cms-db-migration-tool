package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Minimal read-only client for a single CouchDB database, using nothing but the JDK's built-in
 * HttpClient and CouchDB's plain HTTP API, so the tool needs no CouchDB client library.
 */
public class CouchDbClient {

    private static final int PAGE_SIZE = 500;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI dbBaseUri;
    private final String authHeader;

    public CouchDbClient(String baseUrl, String user, String password) {
        this.dbBaseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        String credentials = user + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Fetch every document in the database via paginated {@code _all_docs?include_docs=true}
     * calls, skipping CouchDB design documents ({@code _design/*}). Holds the full result set in
     * memory -- fine for advisory-scale data; page further (e.g. spill per-type batches straight
     * to Postgres) if a real dataset turns out to be too large for that.
     */
    public List<JsonNode> fetchAllDocuments() throws IOException, InterruptedException {
        List<JsonNode> allDocs = new ArrayList<>();
        int skip = 0;
        while (true) {
            JsonNode page = fetchPage(skip, PAGE_SIZE);
            JsonNode rows = page.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                break;
            }
            for (JsonNode row : rows) {
                String id = row.path("id").asText("");
                if (id.startsWith("_design/")) {
                    continue;
                }
                JsonNode doc = row.path("doc");
                if (doc.isMissingNode() || doc.isNull()) {
                    continue;
                }
                allDocs.add(doc);
            }
            if (rows.size() < PAGE_SIZE) {
                break;
            }
            skip += PAGE_SIZE;
        }
        return allDocs;
    }

    private JsonNode fetchPage(int skip, int limit) throws IOException, InterruptedException {
        URI uri = URI.create(dbBaseUri + "/_all_docs?include_docs=true&limit=" + limit + "&skip=" + skip);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("CouchDB request failed with status " + response.statusCode()
                    + " for " + uri + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }
}
