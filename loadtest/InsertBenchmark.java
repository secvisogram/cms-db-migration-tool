import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.bsi.secvisogram.csafmigrator.PostgresWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Times the migrator's own PostgreSQL insert of real advisories, on its own, without CouchDB. Every run
 * inserts the same advisories inside a transaction that is rolled back, so the database stays empty. It
 * compares three variants, to see where the insert time goes:
 * <ul>
 *   <li>A: as the migrator did before ({@code reWriteBatchedInserts=false})</li>
 *   <li>B: with {@code reWriteBatchedInserts=true}, the migrator's setting now</li>
 *   <li>C: like B, but with the GIN index on csaf dropped inside the transaction (DDL is transactional, so
 *       the rollback restores it), which shows what that index costs</li>
 * </ul>
 * The advisories are read from the unpacked archive (--source-dir), spread evenly over the years, skipping
 * those the seeder skips because CouchDB could not have stored them either.
 *
 * <p>Run with the shaded migrator jar on the classpath:
 * {@code java -Xmx3g -cp target/csaf-couchdb-postgres-migrator.jar loadtest/InsertBenchmark.java
 * --count=600 --batch=50}</p>
 */
public class InsertBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = new HashMap<>();
        for (String arg : argv) {
            int eq = arg.indexOf('=');
            args.put(arg.substring(2, eq < 0 ? arg.length() : eq), eq < 0 ? "true" : arg.substring(eq + 1));
        }
        Path sourceDir = Path.of(args.getOrDefault("source-dir", "loadtest/data/archive"));
        int count = Integer.parseInt(args.getOrDefault("count", "600"));
        int batch = Integer.parseInt(args.getOrDefault("batch", "50"));
        int maxBytes = Integer.parseInt(args.getOrDefault("max-doc-bytes", "7500000"));
        String url = args.getOrDefault("pg-url", "jdbc:postgresql://127.0.0.1:15432/csaf");

        List<JsonNode> advisories = loadAdvisories(sourceDir, count, maxBytes);
        long jsonBytes = 0;
        for (JsonNode advisory : advisories) {
            jsonBytes += advisory.path("csaf").toString().length();
        }
        System.out.printf("%d advisories, %.1f MB of csaf JSON (%.0f KB each), inserted in batches of %d.%n",
                advisories.size(), jsonBytes / 1e6, jsonBytes / 1e3 / advisories.size(), batch);
        System.out.printf("%-34s %9s %12s %10s%n", "variant", "time (s)", "advisories/s", "MB/s");

        for (int round = 1; round <= 2; round++) {
            run("A rewrite=false, GIN index", url, false, false, advisories, batch, jsonBytes);
            run("B rewrite=true,  GIN index", url, true, false, advisories, batch, jsonBytes);
            run("C rewrite=true,  GIN dropped", url, true, true, advisories, batch, jsonBytes);
        }
    }

    private static void run(String label, String url, boolean rewrite, boolean dropGin, List<JsonNode> advisories,
                            int batch, long jsonBytes) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "csaf");
        properties.setProperty("password", "csaf");
        properties.setProperty("reWriteBatchedInserts", Boolean.toString(rewrite));
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE TABLE advisories CASCADE");
                if (dropGin) {
                    statement.execute("DROP INDEX idx_advisories_csaf");
                }
            }
            PostgresWriter writer = new PostgresWriter(connection);
            long started = System.nanoTime();
            for (int i = 0; i < advisories.size(); i += batch) {
                writer.insertAdvisories(advisories.subList(i, Math.min(i + batch, advisories.size())));
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            connection.rollback();
            System.out.printf("%-34s %9.1f %12.1f %10.2f%n", label, seconds, advisories.size() / seconds,
                    jsonBytes / 1e6 / seconds);
        }
    }

    /** Advisory documents as the old CMS stored them, built from the Red Hat files. */
    private static List<JsonNode> loadAdvisories(Path sourceDir, int count, int maxBytes) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            files = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        List<JsonNode> advisories = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path file = files.get((int) ((long) i * files.size() / count));
            JsonNode csaf = MAPPER.readTree(Files.readAllBytes(file));
            if (MAPPER.writeValueAsBytes(csaf).length > maxBytes) {
                continue;
            }
            ObjectNode advisory = MAPPER.createObjectNode();
            advisory.put("_id", UUID.nameUUIDFromBytes(file.toString().getBytes()).toString());
            advisory.put("type", "Advisory").put("workflowState", "Published").put("owner", "benchmark")
                    .put("versioningType", "Semantic").put("lastMajorVersion", "1");
            advisory.set("csaf", csaf);
            advisories.add(advisory);
        }
        return advisories;
    }
}
