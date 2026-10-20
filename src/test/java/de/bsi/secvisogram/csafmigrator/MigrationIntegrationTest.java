package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end verification: seeds a throwaway CouchDB container with one document of each of the
 * 7 {@link ObjectType}s (including a comment-answers-comment link), runs {@link Migrator} against
 * a throwaway Postgres container pre-loaded with the target schema, and checks that every row
 * count and a handful of field values (in particular the FK links) survived the trip.
 *
 * <p>This is the only thing in the repo that actually proves the field mapping in
 * {@link de.bsi.secvisogram.csafmigrator.mapping.FieldMappings} round-trips correctly -- treat a
 * failure here as "the mapping is wrong," not "the test is flaky," until proven otherwise.
 */
@Testcontainers
class MigrationIntegrationTest {

    private static final String COUCHDB_USER = "testUser";
    private static final String COUCHDB_PASSWORD = "testPassword";
    private static final String COUCHDB_DB = "csaf_test";
    private static final int COUCHDB_PORT = 5984;

    private static final String ADVISORY_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ADVISORY_VERSION_ID = "22222222-2222-2222-2222-222222222222";
    private static final String COMMENT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String ANSWER_ID = "44444444-4444-4444-4444-444444444444";
    private static final String AUDIT_TRAIL_DOCUMENT_ID = "55555555-5555-5555-5555-555555555555";
    private static final String AUDIT_TRAIL_WORKFLOW_ID = "66666666-6666-6666-6666-666666666666";
    private static final String COMMENT_AUDIT_TRAIL_ID = "77777777-7777-7777-7777-777777777777";

    @Container
    private static final GenericContainer<?> couchDb = new GenericContainer<>("couchdb:3.3.3")
            .withEnv("COUCHDB_USER", COUCHDB_USER)
            .withEnv("COUCHDB_PASSWORD", COUCHDB_PASSWORD)
            .withExposedPorts(COUCHDB_PORT);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("csaf_test")
            .withUsername("test")
            .withPassword("test");

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void seedSourceAndTarget() throws Exception {
        createCouchDbDatabase();
        seedDocuments();
        applySchema();
    }

    @Test
    void migratesEveryDocumentTypeWithReferentialIntegrityIntact() throws Exception {
        MigrationConfig config = new MigrationConfig(
                "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + COUCHDB_DB,
                COUCHDB_USER, COUCHDB_PASSWORD,
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        MigrationReport report = Migrator.migrate(config);

        assertFalse(report.hasMismatches(), "migration report recorded a source/inserted count mismatch");

        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertRowCount(conn, "advisories", 1);
            assertRowCount(conn, "advisory_versions", 1);
            assertRowCount(conn, "comments", 2);
            assertRowCount(conn, "audit_trail_documents", 1);
            assertRowCount(conn, "audit_trail_workflows", 1);
            assertRowCount(conn, "audit_trail_comments", 1);
            assertRowCount(conn, "counters", 2);

            // FK link: the AdvisoryVersion's advisoryReference must land as advisory_versions.advisory_id.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT advisory_id FROM advisory_versions WHERE id = '" + ADVISORY_VERSION_ID + "'")) {
                assertEquals(true, rs.next());
                assertEquals(ADVISORY_ID, rs.getString(1));
            }

            // FK link: the answer comment's answerTo must land as comments.answer_to (pass-2 UPDATE).
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT answer_to FROM comments WHERE id = '" + ANSWER_ID + "'")) {
                assertEquals(true, rs.next());
                assertEquals(COMMENT_ID, rs.getString(1));
            }

            // The top-level comment must NOT have picked up an answer_to of its own.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT answer_to FROM comments WHERE id = '" + COMMENT_ID + "'")) {
                assertEquals(true, rs.next());
                assertNull(rs.getString(1));
            }

            // csaf JSONB round-trips: tracking id extracted via the same path the app queries by.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT csaf->'document'->'tracking'->>'id' FROM advisories WHERE id = '" + ADVISORY_ID + "'")) {
                assertEquals(true, rs.next());
                assertEquals("TEST-001", rs.getString(1));
            }

            // Columns the backend derives from csaf (its list queries and unique index use them).
            assertEquals("TEST-001|Test Advisory|2026-01-05T10:00:00Z", listColumns(conn, "advisories", ADVISORY_ID));
            // An unparseable release date becomes NULL, like the backend does.
            assertEquals("TEST-001|Test Advisory v1|null", listColumns(conn, "advisory_versions", ADVISORY_VERSION_ID));

            // created_at backfilled from the earliest audit-trail row; entities without one keep now().
            assertEquals("2026-01-01", createdAtDate(conn, "advisories", ADVISORY_ID));
            assertEquals("2026-01-03", createdAtDate(conn, "comments", COMMENT_ID));
            assertNotEquals("2026-01-03", createdAtDate(conn, "comments", ANSWER_ID));
            // Without audit rows (this comment, and every advisory version) created_at is the import time.
            assertEquals(true, createdRecently(conn, "comments", ANSWER_ID));
            assertEquals(true, createdRecently(conn, "advisory_versions", ADVISORY_VERSION_ID));

            // Counter values carried over correctly.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT count FROM counters WHERE id = 'TMP_TRACKING_ID_COUNTER'")) {
                assertEquals(true, rs.next());
                assertEquals(5L, rs.getLong(1));
            }
        }

        report.print();
    }

    @Test
    void groupedPassesWorkAcrossManySmallPages() throws Exception {
        // Page size 2 splits the 9 documents over 5 pages, each mixing several types, so every write
        // group has to pick its own types out of pages that also contain others.
        MigrationConfig config = new MigrationConfig(
                "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + COUCHDB_DB,
                COUCHDB_USER, COUCHDB_PASSWORD,
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), false, false, 2);

        MigrationReport report = Migrator.migrate(config);

        assertFalse(report.hasMismatches(), "migration report recorded a source/inserted count mismatch");
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertRowCount(conn, "advisories", 1);
            assertRowCount(conn, "advisory_versions", 1);
            assertRowCount(conn, "comments", 2);
            assertRowCount(conn, "audit_trail_documents", 1);
            assertRowCount(conn, "audit_trail_workflows", 1);
            assertRowCount(conn, "audit_trail_comments", 1);
            assertRowCount(conn, "counters", 2);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT answer_to FROM comments WHERE id = '" + ANSWER_ID + "'")) {
                assertEquals(true, rs.next());
                assertEquals(COMMENT_ID, rs.getString(1));
            }
        }
    }

    @Test
    void projectedReadReturnsOnlyTheSmallFieldsAndEveryDocumentOnce() throws Exception {
        CouchDbClient client = new CouchDbClient(
                "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + COUCHDB_DB,
                COUCHDB_USER, COUCHDB_PASSWORD, 2);
        List<JsonNode> docs = new ArrayList<>();
        client.forEachProjectedDocument(docs::add);

        assertEquals(9, docs.size());
        assertEquals(9, docs.stream().map(d -> d.path("_id").asText()).collect(java.util.stream.Collectors.toSet()).size());
        JsonNode advisory = docs.stream().filter(d -> d.path("_id").asText().equals(ADVISORY_ID)).findFirst().orElseThrow();
        assertEquals("TEST-001", advisory.path("csaf").path("document").path("tracking").path("id").asText());
        assertFalse(advisory.path("csaf").path("document").has("title"), "the csaf body must not be downloaded");

        assertEquals(java.util.Set.of(ADVISORY_ID, ADVISORY_VERSION_ID), client.findIdsWithCsafObject());
    }

    @Test
    void projectedAndFullValidationReadsGiveTheSameResult() throws Exception {
        String database = "csaf_malformed";
        createCouchDbDatabase(database);
        String ok = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String advisoryFields = "\"workflowState\": \"Draft\", \"owner\": \"u\", \"versioningType\": \"Semantic\"";
        putDoc(database, ok, "{\"type\": \"Advisory\", " + advisoryFields
                + ", \"csaf\": {\"document\": {\"tracking\": {\"id\": \"OK-1\"}}}}");
        // csaf is an object without a tracking id: valid, and must not be mistaken for a missing csaf.
        putDoc(database, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "{\"type\": \"Advisory\", " + advisoryFields + ", \"csaf\": {}}");
        String csafString = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        putDoc(database, csafString, "{\"type\": \"Advisory\", " + advisoryFields + ", \"csaf\": \"nope\"}");
        putDoc(database, "dddddddd-dddd-dddd-dddd-dddddddddddd",
                "{\"type\": \"Advisory\", " + advisoryFields + "}");
        putDoc(database, "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                "{\"type\": \"Advisory\", " + advisoryFields + ", \"csaf\": null}");
        // a version without csaf, a version of a missing advisory, and a comment on a rejected advisory
        putDoc(database, "11111111-0000-0000-0000-000000000001", "{\"type\": \"AdvisoryVersion\", \"advisoryReference\": \""
                + ok + "\", " + advisoryFields + "}");
        putDoc(database, "11111111-0000-0000-0000-000000000002", "{\"type\": \"AdvisoryVersion\", \"advisoryReference\": \""
                + "99999999-9999-9999-9999-999999999999\", " + advisoryFields + ", \"csaf\": {}}");
        putDoc(database, "11111111-0000-0000-0000-000000000003", "{\"type\": \"Comment\", \"advisoryId\": \""
                + csafString + "\", \"owner\": \"u\", \"commentText\": \"t\"}");
        putDoc(database, "TMP_TRACKING_ID_COUNTER", "{\"type\": \"Counter\", \"count\": 1}");
        putDoc(database, "11111111-0000-0000-0000-000000000004", "{\"type\": \"Mystery\"}");

        String url = "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + database;
        // skipMalformed + dryRun: nothing is written, and "inserted" is what would be loaded.
        MigrationReport full = Migrator.migrate(new MigrationConfig(url, COUCHDB_USER, COUCHDB_PASSWORD,
                null, null, null, true, true, 2, true));
        MigrationReport projected = Migrator.migrate(new MigrationConfig(url, COUCHDB_USER, COUCHDB_PASSWORD,
                null, null, null, true, true, 2, false));

        assertFalse(full.errors().isEmpty());
        assertEquals(full.errors(), projected.errors());
        assertEquals(full.sourceCounts(), projected.sourceCounts());
        assertEquals(full.insertedCounts(), projected.insertedCounts());
        // The two advisories with a usable csaf object survive, the three without one are rejected.
        assertEquals(2, projected.insertedCounts().get("Advisory"));
        // Both versions are rejected (no csaf; advisory missing), and so is the comment on a rejected advisory.
        assertEquals(0, projected.insertedCounts().getOrDefault("AdvisoryVersion", 0).intValue());
        assertEquals(0, projected.insertedCounts().getOrDefault("Comment", 0).intValue());
    }

    @Test
    void fetchingByIdReturnsExactlyTheRequestedExistingDocuments() throws Exception {
        String database = "csaf_by_id";
        createCouchDbDatabase(database);
        putDoc(database, "aaaaaaaa-0000-0000-0000-000000000001", "{\"type\": \"Counter\", \"count\": 1}");
        putDoc(database, "aaaaaaaa-0000-0000-0000-000000000002", "{\"type\": \"Counter\", \"count\": 2}");
        putDoc(database, "aaaaaaaa-0000-0000-0000-000000000003", "{\"type\": \"Counter\", \"count\": 3}");
        putDoc(database, "aaaaaaaa-0000-0000-0000-000000000004", "{\"type\": \"Counter\", \"count\": 4}");
        // A deleted document: CouchDB still lists its id but has no document for it.
        String revision = putDocRevision(database, "aaaaaaaa-0000-0000-0000-000000000005", "{\"type\": \"Counter\", \"count\": 5}");
        deleteDoc(database, "aaaaaaaa-0000-0000-0000-000000000005", revision);

        CouchDbClient client = new CouchDbClient(
                "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + database,
                COUCHDB_USER, COUCHDB_PASSWORD, 500);
        List<String> found = new ArrayList<>();
        List<Integer> chunkSizes = new ArrayList<>();
        client.forEachByIds(List.of("aaaaaaaa-0000-0000-0000-000000000003", "aaaaaaaa-0000-0000-0000-000000000001",
                        "aaaaaaaa-0000-0000-0000-000000000099", "aaaaaaaa-0000-0000-0000-000000000005",
                        "aaaaaaaa-0000-0000-0000-000000000004"), 2,
                page -> {
                    chunkSizes.add(page.size());
                    page.forEach(doc -> found.add(doc.path("_id").asText()));
                });

        // Unknown and deleted ids are skipped; the rest comes back once each, in the order asked for.
        assertEquals(List.of("aaaaaaaa-0000-0000-0000-000000000003", "aaaaaaaa-0000-0000-0000-000000000001",
                "aaaaaaaa-0000-0000-0000-000000000004"), found);
        assertEquals(List.of(2, 1), chunkSizes);
    }

    @Test
    void keysetPagingVisitsEveryDocumentExactlyOnce() throws Exception {
        // 9 seeded documents with a page size of 2: several pages plus a short last page.
        CouchDbClient client = new CouchDbClient(
                "http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + "/" + COUCHDB_DB,
                COUCHDB_USER, COUCHDB_PASSWORD, 2);
        List<String> ids = new ArrayList<>();
        client.forEachDocument(doc -> ids.add(doc.path("_id").asText()));

        assertEquals(9, ids.size());
        assertEquals(9, new HashSet<>(ids).size(), "a document was returned more than once");
    }

    private static String listColumns(Connection conn, String table, String id) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tracking_id || '|' || document_title || '|' || "
                             + "coalesce(to_char(current_release_date AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), 'null') "
                             + "FROM " + table + " WHERE id = '" + id + "'")) {
            assertEquals(true, rs.next());
            return rs.getString(1);
        }
    }

    private static boolean createdRecently(Connection conn, String table, String id) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT created_at > now() - interval '10 minutes' FROM " + table
                     + " WHERE id = '" + id + "'")) {
            assertEquals(true, rs.next());
            return rs.getBoolean(1);
        }
    }

    private static String createdAtDate(Connection conn, String table, String id) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM " + table + " WHERE id = '" + id + "'")) {
            assertEquals(true, rs.next());
            return rs.getString(1);
        }
    }

    private static void assertRowCount(Connection conn, String table, int expected) throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            assertEquals(expected, rs.getInt(1), "unexpected row count in " + table);
        }
    }

    private static void createCouchDbDatabase() throws Exception {
        createCouchDbDatabase(COUCHDB_DB);
    }

    private static void createCouchDbDatabase(String database) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(couchDbUri("/" + database))
                .header("Authorization", basicAuth())
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 412) {
            throw new IllegalStateException("Failed to create CouchDB database: " + response.statusCode() + " " + response.body());
        }
    }

    private static void seedDocuments() throws Exception {
        putDoc(ADVISORY_ID, """
                {
                  "type": "Advisory",
                  "workflowState": "Draft",
                  "owner": "test-user",
                  "csaf": {"document": {"title": "Test Advisory", "tracking": {"id": "TEST-001",
                           "current_release_date": "2026-01-05T10:00:00Z"}}},
                  "versioningType": "Semantic",
                  "lastMajorVersion": "1",
                  "tmpTrackingId": "TEMP-001"
                }
                """);
        putDoc(ADVISORY_VERSION_ID, """
                {
                  "type": "AdvisoryVersion",
                  "advisoryReference": "%s",
                  "workflowState": "Draft",
                  "owner": "test-user",
                  "csaf": {"document": {"title": "Test Advisory v1", "tracking": {"id": "TEST-001",
                           "current_release_date": "not-a-date"}}},
                  "versioningType": "Semantic",
                  "lastMajorVersion": "1"
                }
                """.formatted(ADVISORY_ID));
        putDoc(COMMENT_ID, """
                {
                  "type": "Comment",
                  "advisoryId": "%s",
                  "owner": "reviewer",
                  "commentText": "Please fix section 2",
                  "csafNodeId": "node-1",
                  "fieldName": "title"
                }
                """.formatted(ADVISORY_ID));
        putDoc(ANSWER_ID, """
                {
                  "type": "Comment",
                  "advisoryId": "%s",
                  "owner": "author",
                  "commentText": "Fixed, thanks",
                  "answerTo": "%s"
                }
                """.formatted(ADVISORY_ID, COMMENT_ID));
        putDoc(AUDIT_TRAIL_DOCUMENT_ID, """
                {
                  "type": "AuditTrailDocument",
                  "advisoryId": "%s",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "user": "test-user",
                  "changeType": "Update",
                  "diff": [{"op": "replace", "path": "/document/title", "value": "Test Advisory"}],
                  "oldDocVersion": "0",
                  "docVersion": "1"
                }
                """.formatted(ADVISORY_ID));
        putDoc(AUDIT_TRAIL_WORKFLOW_ID, """
                {
                  "type": "AuditTrailWorkflow",
                  "advisoryId": "%s",
                  "createdAt": "2026-01-02T00:00:00Z",
                  "user": "test-user",
                  "changeType": "StateChanged",
                  "oldState": "Draft",
                  "newState": "Review",
                  "oldDocVersion": "1",
                  "docVersion": "1"
                }
                """.formatted(ADVISORY_ID));
        putDoc(COMMENT_AUDIT_TRAIL_ID, """
                {
                  "type": "CommentAuditTrail",
                  "commentId": "%s",
                  "createdAt": "2026-01-03T00:00:00Z",
                  "user": "reviewer",
                  "changeType": "Create",
                  "commentText": "Please fix section 2"
                }
                """.formatted(COMMENT_ID));
        putDoc("TMP_TRACKING_ID_COUNTER", """
                {"type": "Counter", "count": 5}
                """);
        putDoc("FINAL_TRACKING_ID_COUNTER", """
                {"type": "Counter", "count": 2}
                """);
    }

    private static void putDoc(String id, String json) throws Exception {
        putDoc(COUCHDB_DB, id, json);
    }

    /** Creates the document and returns its revision. */
    private static String putDocRevision(String database, String id, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(couchDbUri("/" + database + "/" + id))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Failed to seed doc " + id + ": " + response.statusCode() + " " + response.body());
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("rev").asText();
    }

    private static void deleteDoc(String database, String id, String revision) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(couchDbUri("/" + database + "/" + id + "?rev=" + revision))
                .header("Authorization", basicAuth())
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to delete doc " + id + ": " + response.statusCode() + " " + response.body());
        }
    }

    private static void putDoc(String database, String id, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(couchDbUri("/" + database + "/" + id))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Failed to seed doc " + id + ": " + response.statusCode() + " " + response.body());
        }
    }

    private static URI couchDbUri(String path) {
        return URI.create("http://" + couchDb.getHost() + ":" + couchDb.getMappedPort(COUCHDB_PORT) + path);
    }

    private static String basicAuth() {
        String credentials = COUCHDB_USER + ":" + COUCHDB_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static void applySchema() throws Exception {
        String ddl = Files.readString(Path.of(
                MigrationIntegrationTest.class.getResource("/V1__initial_schema.sql").toURI()));
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }
}
