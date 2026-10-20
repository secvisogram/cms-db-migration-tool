import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Fills a CouchDB database with a large, realistic dataset for load-testing the migrator.
 *
 * <p>The advisories are Red Hat's public CSAF documents. They are downloaded once into
 * {@code --source-dir} (never committed) and then turned into the CouchDB documents the old
 * csaf-cms-backend wrote: Advisory, AdvisoryVersion, Comment, the three audit-trail types and the two
 * Counter documents. {@code --copies=K} repeats the whole set K times with fresh ids and rewritten
 * tracking ids so the dataset can be scaled up without clashing tracking ids.</p>
 *
 * <p>Run it with the shaded migrator jar on the classpath, which already contains Jackson:
 * {@code java -cp target/csaf-couchdb-postgres-migrator.jar loadtest/LoadTestSeeder.java --help}</p>
 */
public class LoadTestSeeder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final String[] STATES = {"Published", "Draft", "Review", "Approved", "RfPublication"};
    private static final int[] STATE_WEIGHTS = {70, 10, 8, 6, 6};
    private static final long MAX_BATCH_BYTES = 8L * 1024 * 1024;

    // -- options -------------------------------------------------------------------------------

    private final String couchDbUrl;
    private final String authHeader;
    private final Path sourceDir;
    private final String sourceBaseUrl;
    private final int limit;
    private final int copies;
    private final boolean offline;
    private final int downloadThreads;
    private final int batchSize;
    private final int versions;
    private final int comments;
    private final int auditDocuments;
    private final int auditWorkflows;
    private final int answerPercent;
    private final int maxDocBytes;
    private long skippedAdvisories;

    private final Map<String, Long> created = new TreeMap<>();
    private final List<byte[]> batch = new ArrayList<>();
    private long batchBytes;

    private LoadTestSeeder(Map<String, String> args) {
        String url = require(args, "couchdb-url");
        this.couchDbUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String credentials = args.getOrDefault("couchdb-user", "admin") + ":" + args.getOrDefault("couchdb-password", "admin");
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        this.sourceDir = Path.of(args.getOrDefault("source-dir", "loadtest/data"));
        this.sourceBaseUrl = args.getOrDefault("source-url", "https://security.access.redhat.com/data/csaf/v2/advisories/");
        this.limit = Integer.parseInt(args.getOrDefault("limit", "0"));
        this.copies = Integer.parseInt(args.getOrDefault("copies", "1"));
        this.offline = args.containsKey("offline");
        this.downloadThreads = Integer.parseInt(args.getOrDefault("download-threads", "8"));
        this.batchSize = Integer.parseInt(args.getOrDefault("batch-size", "100"));
        this.versions = Integer.parseInt(args.getOrDefault("versions", "2"));
        this.comments = Integer.parseInt(args.getOrDefault("comments", "3"));
        this.auditDocuments = Integer.parseInt(args.getOrDefault("audit-documents", "4"));
        this.auditWorkflows = Integer.parseInt(args.getOrDefault("audit-workflows", "3"));
        this.answerPercent = Integer.parseInt(args.getOrDefault("answer-percent", "30"));
        this.maxDocBytes = Integer.parseInt(args.getOrDefault("max-doc-bytes", "7500000"));
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String arg : argv) {
            if (arg.equals("--help")) {
                System.out.println(USAGE);
                return;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unrecognized argument: " + arg + "\n" + USAGE);
            }
            int eq = arg.indexOf('=');
            args.put(eq < 0 ? arg.substring(2) : arg.substring(2, eq), eq < 0 ? "true" : arg.substring(eq + 1));
        }
        new LoadTestSeeder(args).run();
    }

    private void run() throws Exception {
        List<Path> files = offline ? listLocalFiles() : downloadAdvisories();
        if (files.isEmpty()) {
            throw new IllegalStateException("No advisory files found in " + sourceDir);
        }
        System.out.println(files.size() + " advisory files, " + copies + " copies.");

        createDatabase();
        long started = System.nanoTime();
        long advisoryCount = 0;
        for (int copy = 0; copy < copies; copy++) {
            for (Path file : files) {
                String relativePath = sourceDir.relativize(file).toString().replace('\\', '/');
                JsonNode csaf = MAPPER.readTree(Files.readAllBytes(file));
                // The advisory and its versions all embed the csaf document. CouchDB rejects documents above
                // its max_document_size (8 MB by default), so the old backend could never have stored such an
                // advisory. Skip it together with everything that belongs to it, so no orphans are left.
                int csafBytes = MAPPER.writeValueAsBytes(csaf).length;
                if (csafBytes > maxDocBytes) {
                    skippedAdvisories++;
                    System.out.printf("  skipped %s (%.1f MB, over --max-doc-bytes)%n", relativePath, csafBytes / 1e6);
                    continue;
                }
                addAdvisoryDocuments(relativePath, copy, csaf);
                advisoryCount++;
                if (advisoryCount % 1000 == 0) {
                    System.out.printf("  %d advisories seeded (%.0f s)%n", advisoryCount,
                            (System.nanoTime() - started) / 1e9);
                }
            }
        }
        add("Counter", counter("TMP_TRACKING_ID_COUNTER", advisoryCount));
        add("Counter", counter("FINAL_TRACKING_ID_COUNTER", advisoryCount));
        flush();
        markSeedComplete(advisoryCount);

        long total = created.values().stream().mapToLong(Long::longValue).sum();
        System.out.printf("Done in %.0f s. %d advisories skipped as too large for CouchDB. "
                        + "Expected documents per type (%d total):%n",
                (System.nanoTime() - started) / 1e9, skippedAdvisories, total);
        created.forEach((type, count) -> System.out.printf("  %-20s %d%n", type, count));
    }

    // -- source files --------------------------------------------------------------------------

    private List<Path> listLocalFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            return sample(paths.filter(p -> p.toString().endsWith(".json")).sorted().toList());
        }
    }

    /** Downloads index.txt and every advisory listed in it that is not cached yet. */
    private List<Path> downloadAdvisories() throws Exception {
        Files.createDirectories(sourceDir);
        String base = sourceBaseUrl.endsWith("/") ? sourceBaseUrl : sourceBaseUrl + "/";
        String index = new String(get(URI.create(base + "index.txt")), StandardCharsets.UTF_8);
        List<String> paths = index.lines().map(String::trim).filter(l -> l.matches("\\d{4}/.+\\.json")).toList();
        paths = sample(paths);
        System.out.println("Index lists " + paths.size() + " advisories to use.");

        Semaphore concurrency = new Semaphore(downloadThreads);
        List<Path> files = new ArrayList<>();
        List<Future<?>> pending = new ArrayList<>();
        int[] downloaded = {0};
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String relative : paths) {
                Path target = sourceDir.resolve(relative);
                files.add(target);
                if (Files.exists(target)) {
                    continue;
                }
                pending.add(executor.submit(() -> {
                    concurrency.acquire();
                    try {
                        byte[] body = get(URI.create(base + relative));
                        Files.createDirectories(target.getParent());
                        Path partial = target.resolveSibling(target.getFileName() + ".part");
                        Files.write(partial, body);
                        Files.move(partial, target);
                        synchronized (downloaded) {
                            if (++downloaded[0] % 500 == 0) {
                                System.out.println("  downloaded " + downloaded[0] + " advisories");
                            }
                        }
                        return null;
                    } finally {
                        concurrency.release();
                    }
                }));
            }
            for (Future<?> f : pending) {
                f.get();
            }
        }
        System.out.println("Downloaded " + downloaded[0] + ", the rest were cached in " + sourceDir + ".");
        return files.stream().sorted().toList();
    }

    /**
     * Keeps {@code --limit} entries spread evenly over the whole (year-sorted) list instead of the
     * first N, which would be only the oldest and smallest advisories.
     */
    private <T> List<T> sample(List<T> all) {
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        List<T> picked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            picked.add(all.get((int) ((long) i * all.size() / limit)));
        }
        return picked;
    }

    private static byte[] get(URI uri) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                HttpResponse<byte[]> response = HTTP.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IOException("HTTP " + response.statusCode() + " for " + uri);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1000L << (attempt - 1));
        }
        throw last;
    }

    // -- document generation -------------------------------------------------------------------

    private void addAdvisoryDocuments(String path, int copy, JsonNode csaf) throws IOException, InterruptedException {
        Random random = new Random((path + "#" + copy).hashCode());
        ObjectNode csafNode = (ObjectNode) csaf;
        if (copy > 0 && csafNode.path("document").path("tracking") instanceof ObjectNode tracking
                && tracking.path("id").isTextual()) {
            tracking.put("id", tracking.path("id").asText() + "-c" + copy);
        }

        String advisoryId = uuid(path, copy, "advisory", 0);
        String state = pick(random);
        String owner = "loadtest-user-" + random.nextInt(20);
        String lastMajorVersion = csafNode.path("document").path("tracking").path("version").asText("1");

        ObjectNode advisory = doc("Advisory", advisoryId);
        advisory.put("workflowState", state).put("owner", owner).put("versioningType", "Semantic")
                .put("lastMajorVersion", lastMajorVersion);
        if (!state.equals("Published")) {
            advisory.put("tmpTrackingId", "TMP-" + copy + "-" + Math.abs(path.hashCode()));
        }
        advisory.set("csaf", csafNode);
        add("Advisory", advisory);

        for (int v = 0; v < versions; v++) {
            ObjectNode version = doc("AdvisoryVersion", uuid(path, copy, "version", v));
            version.put("advisoryReference", advisoryId).put("workflowState", "Published").put("owner", owner)
                    .put("versioningType", "Semantic").put("lastMajorVersion", lastMajorVersion);
            version.set("csaf", csafNode);
            add("AdvisoryVersion", version);
        }

        Instant base = Instant.parse("2025-01-01T00:00:00Z").plusSeconds(random.nextInt(86_400 * 300));
        List<String> commentIds = new ArrayList<>();
        for (int c = 0; c < comments; c++) {
            String commentId = uuid(path, copy, "comment", c);
            ObjectNode comment = doc("Comment", commentId);
            comment.put("advisoryId", advisoryId).put("owner", "reviewer-" + random.nextInt(5))
                    .put("commentText", "Load test comment " + c + " on " + path)
                    .put("csafNodeId", "node-" + c).put("fieldName", "title");
            if (!commentIds.isEmpty() && random.nextInt(100) < answerPercent) {
                comment.put("answerTo", commentIds.get(random.nextInt(commentIds.size())));
            }
            commentIds.add(commentId);
            add("Comment", comment);

            ObjectNode commentAudit = doc("CommentAuditTrail", uuid(path, copy, "commentaudit", c));
            commentAudit.put("commentId", commentId).put("createdAt", base.plusSeconds(c * 3600L).toString())
                    .put("user", "reviewer-" + random.nextInt(5)).put("changeType", "Create")
                    .put("commentText", "Load test comment " + c + " on " + path);
            add("CommentAuditTrail", commentAudit);
        }

        for (int d = 0; d < auditDocuments; d++) {
            ObjectNode audit = doc("AuditTrailDocument", uuid(path, copy, "auditdoc", d));
            audit.put("advisoryId", advisoryId).put("createdAt", base.plusSeconds(d * 7200L).toString())
                    .put("user", owner).put("changeType", d == 0 ? "Create" : "Update");
            ArrayNode diff = audit.putArray("diff");
            diff.addObject().put("op", "replace").put("path", "/document/title").put("value", "Revision " + d);
            audit.put("oldDocVersion", Integer.toString(d)).put("docVersion", Integer.toString(d + 1));
            add("AuditTrailDocument", audit);
        }

        for (int w = 0; w < auditWorkflows; w++) {
            ObjectNode audit = doc("AuditTrailWorkflow", uuid(path, copy, "auditwf", w));
            audit.put("advisoryId", advisoryId).put("createdAt", base.plusSeconds(w * 5400L).toString())
                    .put("user", owner).put("changeType", "StateChanged")
                    .put("oldState", STATES[Math.min(w + 1, 3)]).put("newState", STATES[Math.min(w + 2, 4)])
                    .put("oldDocVersion", Integer.toString(w)).put("docVersion", Integer.toString(w + 1));
            add("AuditTrailWorkflow", audit);
        }
    }

    private static ObjectNode counter(String id, long count) {
        ObjectNode counter = MAPPER.createObjectNode();
        counter.put("_id", id).put("type", "Counter").put("count", count);
        return counter;
    }

    private static ObjectNode doc(String type, String id) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("_id", id).put("type", type);
        return doc;
    }

    private static String uuid(String path, int copy, String kind, int n) {
        return UUID.nameUUIDFromBytes((path + "#" + copy + "#" + kind + "#" + n).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String pick(Random random) {
        int roll = random.nextInt(100);
        for (int i = 0, sum = 0; i < STATES.length; i++) {
            sum += STATE_WEIGHTS[i];
            if (roll < sum) {
                return STATES[i];
            }
        }
        return STATES[0];
    }

    // -- CouchDB -------------------------------------------------------------------------------

    private void createDatabase() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(couchDbUrl))
                .header("Authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 412) {
            throw new IOException("Could not create database: " + response.statusCode() + " " + response.body());
        }
    }

    /** A _local document is not counted in doc_count or listed by _all_docs, so it does not disturb the migrator. */
    private void markSeedComplete(long advisories) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(couchDbUrl + "/_local/loadtest-seed-complete"))
                        .header("Authorization", authHeader).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"advisories\":" + advisories + "}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IOException("Could not write the seed marker: " + response.statusCode() + " " + response.body());
        }
    }

    private void add(String type, ObjectNode doc) throws IOException, InterruptedException {
        byte[] bytes = MAPPER.writeValueAsBytes(doc);
        batch.add(bytes);
        batchBytes += bytes.length;
        created.merge(type, 1L, Long::sum);
        if (batch.size() >= batchSize || batchBytes >= MAX_BATCH_BYTES) {
            flush();
        }
    }

    private void flush() throws IOException, InterruptedException {
        if (batch.isEmpty()) {
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream((int) batchBytes + 64);
        body.writeBytes("{\"docs\":[".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                body.write(',');
            }
            body.writeBytes(batch.get(i));
        }
        body.writeBytes("]}".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(couchDbUrl + "/_bulk_docs"))
                        .header("Authorization", authHeader).header("Content-Type", "application/json")
                        .timeout(Duration.ofMinutes(5))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        // _bulk_docs answers 201 and lists a per-document error for each failure. A conflict means the
        // document was stored by an earlier run, which is what makes re-running after a failure possible.
        if (response.statusCode() != 201) {
            throw new IOException("_bulk_docs failed: " + response.statusCode() + " "
                    + response.body().substring(0, Math.min(500, response.body().length())));
        }
        for (JsonNode result : MAPPER.readTree(response.body())) {
            if (result.has("error") && !"conflict".equals(result.path("error").asText())) {
                throw new IOException("_bulk_docs rejected a document: " + result);
            }
        }
        batch.clear();
        batchBytes = 0;
    }

    // -- cli -----------------------------------------------------------------------------------

    private static String require(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: --" + key + "\n" + USAGE);
        }
        return value;
    }

    private static final String USAGE = """
            Usage: java -cp target/csaf-couchdb-postgres-migrator.jar loadtest/LoadTestSeeder.java \\
                     --couchdb-url=http://localhost:15984/csaf_load [options]

              --couchdb-user=admin --couchdb-password=admin   CouchDB credentials (default admin/admin)
              --source-dir=loadtest/data     where advisories are cached (default loadtest/data)
              --source-url=<url>             advisory source (default Red Hat's CSAF v2 advisories)
              --offline                      use the files already in --source-dir, no download
              --limit=N                      use N advisories spread evenly over the index (default: all)
              --copies=K                     repeat the set K times with new ids (default 1)
              --download-threads=8           parallel downloads
              --batch-size=100               documents per _bulk_docs request
              --versions=2 --comments=3 --audit-documents=4 --audit-workflows=3   documents per advisory
              --answer-percent=30            share of comments that answer an earlier comment
              --max-doc-bytes=7500000        skip advisories whose csaf is larger (CouchDB's limit is 8 MB)
            """;
}
