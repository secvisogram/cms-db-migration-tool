package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsi.secvisogram.csafmigrator.mapping.FieldMappings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actual read-group-write pipeline, split out from {@link Main} so it can be exercised
 * directly from a test without going through CLI argument parsing or {@code System.exit}.
 */
public final class Migrator {

    private Migrator() {
    }

    public static MigrationReport migrate(MigrationConfig config) throws Exception {
        System.out.println("Reading documents from " + config.couchdbUrl + " ...");
        CouchDbClient couchDb = new CouchDbClient(config.couchdbUrl, config.couchdbUser, config.couchdbPassword);
        List<JsonNode> allDocs = couchDb.fetchAllDocuments();
        System.out.println("Read " + allDocs.size() + " documents.");

        Map<String, List<JsonNode>> sourceByType = groupByType(allDocs);

        MigrationReport report = new MigrationReport();
        sourceByType.forEach((type, docs) -> report.recordSource(type, docs.size()));

        // Validate everything before touching Postgres. Malformed documents abort the run unless
        // --skip-malformed was given; duplicate tracking IDs always abort, since there is no way
        // to know which of the clashing advisories should win.
        Map<String, List<JsonNode>> byType = DocumentValidator.validate(sourceByType, report);
        boolean malformed = report.hasErrors();
        int errorsBeforeDuplicateCheck = report.errorCount();
        checkDuplicateTrackingIds(byType.getOrDefault(ObjectType.Advisory.name(), List.of()), report);
        boolean duplicates = report.errorCount() > errorsBeforeDuplicateCheck;
        if (duplicates || (malformed && !config.skipMalformed)) {
            return report;
        }
        if (config.dryRun) {
            byType.forEach((type, docs) -> report.recordInserted(type, docs.size()));
            System.out.println("Dry run: nothing was written to PostgreSQL. 'Inserted' below is what would be loaded.");
            return report;
        }

        try (Connection connection = DriverManager.getConnection(config.pgUrl, config.pgUser, config.pgPassword)) {
            connection.setAutoCommit(false);
            PostgresWriter writer = new PostgresWriter(connection);

            try {
                writer.truncateAll();

                List<JsonNode> advisories = byType.getOrDefault(ObjectType.Advisory.name(), List.of());
                report.recordInserted(ObjectType.Advisory.name(), writer.insertAdvisories(advisories));

                List<JsonNode> advisoryVersions = byType.getOrDefault(ObjectType.AdvisoryVersion.name(), List.of());
                report.recordInserted(ObjectType.AdvisoryVersion.name(), writer.insertAdvisoryVersions(advisoryVersions));

                List<JsonNode> comments = byType.getOrDefault(ObjectType.Comment.name(), List.of());
                report.recordInserted(ObjectType.Comment.name(), writer.insertComments(comments));
                writer.linkCommentAnswers(comments);

                List<JsonNode> auditTrailDocuments = byType.getOrDefault(ObjectType.AuditTrailDocument.name(), List.of());
                report.recordInserted(ObjectType.AuditTrailDocument.name(), writer.insertAuditTrailDocuments(auditTrailDocuments));

                List<JsonNode> auditTrailWorkflows = byType.getOrDefault(ObjectType.AuditTrailWorkflow.name(), List.of());
                report.recordInserted(ObjectType.AuditTrailWorkflow.name(), writer.insertAuditTrailWorkflows(auditTrailWorkflows));

                List<JsonNode> commentAuditTrails = byType.getOrDefault(ObjectType.CommentAuditTrail.name(), List.of());
                report.recordInserted(ObjectType.CommentAuditTrail.name(), writer.insertAuditTrailComments(commentAuditTrails));

                List<JsonNode> counters = byType.getOrDefault(ObjectType.Counter.name(), List.of());
                report.recordInserted(ObjectType.Counter.name(), writer.insertCounters(counters));

                writer.backfillCreatedAt();

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }

        return report;
    }

    static void checkDuplicateTrackingIds(List<JsonNode> advisories, MigrationReport report) {
        Map<String, String> firstSeen = new LinkedHashMap<>();
        for (JsonNode doc : advisories) {
            JsonNode trackingId = doc.path(FieldMappings.Advisory.CSAF).path("document").path("tracking").path("id");
            if (!trackingId.isTextual()) {
                continue;
            }
            String id = doc.path(FieldMappings.ID).asText("<no id>");
            String other = firstSeen.putIfAbsent(trackingId.asText(), id);
            if (other != null) {
                report.recordError(id, "duplicate tracking id '" + trackingId.asText() + "' (also on advisory " + other + ")");
            }
        }
    }

    private static Map<String, List<JsonNode>> groupByType(List<JsonNode> docs) {
        Map<String, List<JsonNode>> byType = new LinkedHashMap<>();
        for (JsonNode doc : docs) {
            String type = doc.path(FieldMappings.TYPE).asText(null);
            if (type == null) {
                type = "<no type>";
            }
            byType.computeIfAbsent(type, t -> new ArrayList<>()).add(doc);
        }
        return byType;
    }
}
