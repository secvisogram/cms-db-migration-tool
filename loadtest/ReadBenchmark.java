import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two ways of reading the first N pages of a CouchDB database: the full documents via
 * {@code _all_docs?include_docs=true} (what the validation pass does today) and a small projection via
 * {@code POST _find}. Document ids are random UUIDs, so the first pages are a fair sample of the whole
 * database. Each style is run twice, alternating, so a warm cache does not favour the one that runs second.
 *
 * <p>Run with the shaded migrator jar on the classpath, which contains Jackson:
 * {@code java -cp target/csaf-couchdb-postgres-migrator.jar loadtest/ReadBenchmark.java
 * --couchdb-url=http://127.0.0.1:15984/csaf_load --pages=10 --page-size=500}</p>
 */
public class ReadBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final List<String> FIELDS = List.of("_id", "type", "workflowState", "owner", "versioningType",
            "advisoryReference", "commentText", "advisoryId", "answerTo", "createdAt", "user", "changeType",
            "commentId", "count", "csaf.document.tracking.id");

    private static String url;
    private static String auth;

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String arg : argv) {
            int eq = arg.indexOf('=');
            args.put(arg.substring(2, eq < 0 ? arg.length() : eq), eq < 0 ? "true" : arg.substring(eq + 1));
        }
        url = args.getOrDefault("couchdb-url", "http://127.0.0.1:15984/csaf_load");
        url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String credentials = args.getOrDefault("couchdb-user", "admin") + ":" + args.getOrDefault("couchdb-password", "admin");
        auth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        int pages = Integer.parseInt(args.getOrDefault("pages", "10"));
        int pageSize = Integer.parseInt(args.getOrDefault("page-size", "500"));

        System.out.printf("Reading the first %d pages of %d documents, alternating the two styles.%n", pages, pageSize);
        for (int round = 1; round <= 2; round++) {
            report("round " + round + ": _all_docs include_docs ", readAllDocs(pages, pageSize));
            report("round " + round + ": _find projection      ", readProjected(pages, pageSize));
        }
    }

    private static void report(String label, long[] result) {
        System.out.printf("%s %7.1f s  %8d docs  %7.1f MB%n", label, result[0] / 1000.0, result[1], result[2] / 1e6);
    }

    /** @return {milliseconds, documents, bytes} */
    private static long[] readAllDocs(int pages, int pageSize) throws Exception {
        long started = System.nanoTime(), docs = 0, bytes = 0;
        String startKey = null;
        for (int page = 0; page < pages; page++) {
            String startKeyParam = startKey == null ? ""
                    : "&startkey=" + URLEncoder.encode(MAPPER.writeValueAsString(startKey), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/_all_docs?include_docs=true&limit="
                            + (pageSize + 1) + startKeyParam))
                    .header("Authorization", auth).timeout(Duration.ofMinutes(10)).GET().build();
            JsonNode rows = send(request, bytesOut -> bytes0 += bytesOut).path("rows");
            int inPage = Math.min(rows.size(), pageSize);
            docs += inPage;
            if (rows.size() <= pageSize) {
                break;
            }
            startKey = rows.get(pageSize).path("id").asText();
        }
        long totalBytes = bytes0;
        bytes0 = 0;
        return new long[]{(System.nanoTime() - started) / 1_000_000, docs, totalBytes + bytes};
    }

    /** @return {milliseconds, documents, bytes} */
    private static long[] readProjected(int pages, int pageSize) throws Exception {
        long started = System.nanoTime(), docs = 0;
        String bookmark = null;
        for (int page = 0; page < pages; page++) {
            ObjectNode body = MAPPER.createObjectNode();
            body.putObject("selector").putObject("_id").putNull("$gt");
            ArrayNode fields = body.putArray("fields");
            FIELDS.forEach(fields::add);
            body.put("limit", pageSize);
            if (bookmark != null) {
                body.put("bookmark", bookmark);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/_find"))
                    .header("Authorization", auth).header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            JsonNode result = send(request, bytesOut -> bytes0 += bytesOut);
            JsonNode found = result.path("docs");
            docs += found.size();
            if (found.isEmpty()) {
                break;
            }
            bookmark = result.path("bookmark").asText(null);
        }
        long totalBytes = bytes0;
        bytes0 = 0;
        return new long[]{(System.nanoTime() - started) / 1_000_000, docs, totalBytes};
    }

    private static long bytes0;

    private interface ByteCounter {
        void add(long bytes);
    }

    /** Parses the response into a tree, like the migrator does, counting the bytes read. */
    private static JsonNode send(HttpRequest request, ByteCounter counter) throws Exception {
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                        + new String(in.readNBytes(1000), StandardCharsets.UTF_8));
            }
            byte[] body = in.readAllBytes();
            counter.add(body.length);
            return MAPPER.readTree(body);
        }
    }
}
