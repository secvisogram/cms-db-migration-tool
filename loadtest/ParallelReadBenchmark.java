import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures how read speed from CouchDB scales with the number of parallel readers. Every reader walks its
 * own range of the (random UUID) id space with the same full-document {@code _all_docs} request the
 * migrator's write passes use, and parses the answer to a tree like the migrator does. Each run uses
 * fresh id ranges, so a warm cache does not favour the later, more parallel runs.
 *
 * <p>Run with the shaded migrator jar on the classpath, which contains Jackson:
 * {@code java -cp target/csaf-couchdb-postgres-migrator.jar loadtest/ParallelReadBenchmark.java
 * --threads=1,2,4,8 --pages-per-thread=3}</p>
 */
public class ParallelReadBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final String HEX = "0123456789abcdef";

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
        int pagesPerThread = Integer.parseInt(args.getOrDefault("pages-per-thread", "3"));
        int pageSize = Integer.parseInt(args.getOrDefault("page-size", "500"));

        System.out.printf("Each reader reads %d pages of %d full documents from its own id range.%n", pagesPerThread, pageSize);
        System.out.printf("%-8s %8s %10s %10s %10s %10s%n", "readers", "time (s)", "docs", "MB", "MB/s", "speedup");
        double baseline = 0;
        int run = 0;
        for (String count : args.getOrDefault("threads", "1,2,4,8").split(",")) {
            int threads = Integer.parseInt(count.trim());
            long started = System.nanoTime();
            long docs = 0, bytes = 0;
            try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
                List<Future<long[]>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    // Reader i starts at hex digit i*16/threads; the second digit changes with every run.
                    String startKey = "" + HEX.charAt(i * 16 / threads) + HEX.charAt(run % 16);
                    Callable<long[]> reader = () -> read(startKey, pagesPerThread, pageSize);
                    futures.add(executor.submit(reader));
                }
                for (Future<long[]> future : futures) {
                    long[] result = future.get();
                    docs += result[0];
                    bytes += result[1];
                }
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            double throughput = bytes / 1e6 / seconds;
            if (baseline == 0) {
                baseline = throughput;
            }
            System.out.printf("%-8d %8.1f %10d %10.1f %10.2f %9.1fx%n", threads, seconds, docs, bytes / 1e6, throughput,
                    throughput / baseline);
            run++;
        }
    }

    /** @return {documents, bytes} */
    private static long[] read(String firstKey, int pages, int pageSize) throws Exception {
        long docs = 0, bytes = 0;
        String startKey = firstKey;
        for (int page = 0; page < pages; page++) {
            URI uri = URI.create(url + "/_all_docs?include_docs=true&limit=" + (pageSize + 1) + "&startkey="
                    + URLEncoder.encode(MAPPER.writeValueAsString(startKey), StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri).header("Authorization", auth)
                    .timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            JsonNode rows;
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                byte[] body = in.readAllBytes();
                bytes += body.length;
                rows = MAPPER.readTree(body).path("rows");
            }
            docs += Math.min(rows.size(), pageSize);
            if (rows.size() <= pageSize) {
                break;
            }
            startKey = rows.get(pageSize).path("id").asText();
        }
        return new long[]{docs, bytes};
    }
}
