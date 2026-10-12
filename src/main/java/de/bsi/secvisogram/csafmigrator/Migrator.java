package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsi.secvisogram.csafmigrator.mapping.FieldMappings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The actual read-validate-write pipeline, split out from {@link Main} so it can be exercised
 * directly from a test without going through CLI argument parsing or {@code System.exit}.
 *
 * <p>Documents are streamed, never held in full. Phase 1 reads the whole database once and keeps
 * only a small record per document (see {@link DocumentValidator}). Phase 2 reads it again once per
 * document type, in foreign-key order, and inserts the accepted documents page by page.</p>
 */
public final class Migrator {

    /** Insert order matters for foreign keys: parents before the documents that reference them. */
    private static final List<ObjectType> WRITE_ORDER = List.of(
            ObjectType.Advisory,
            ObjectType.AdvisoryVersion,
            ObjectType.Comment,
            ObjectType.AuditTrailDocument,
            ObjectType.AuditTrailWorkflow,
            ObjectType.CommentAuditTrail,
            ObjectType.Counter);

    private Migrator() {
    }

    public static MigrationReport migrate(MigrationConfig config) throws Exception {
        System.out.println("Reading documents from " + config.couchdbUrl + " ...");
        CouchDbClient couchDb = new CouchDbClient(config.couchdbUrl, config.couchdbUser, config.couchdbPassword,
                config.pageSize);

        // Phase 1: validate everything before touching Postgres. Malformed documents abort the run
        // unless --skip-malformed was given; duplicate tracking IDs always abort, since there is no
        // way to know which of the clashing advisories should win.
        MigrationReport report = new MigrationReport();
        DocumentValidator validator = new DocumentValidator(report);
        couchDb.forEachDocument(validator::accept);
        System.out.println("Read " + validator.documentCount() + " documents.");
        validator.sourceCounts().forEach(report::recordSource);

        Map<String, Set<String>> accepted = validator.finish();
        boolean malformed = report.hasErrors();
        int errorsBeforeDuplicateCheck = report.errorCount();
        checkDuplicateTrackingIds(validator.advisoryTrackingIds(), report);
        boolean duplicates = report.errorCount() > errorsBeforeDuplicateCheck;
        if (duplicates || (malformed && !config.skipMalformed)) {
            return report;
        }
        if (config.dryRun) {
            accepted.forEach((type, ids) -> report.recordInserted(type, ids.size()));
            System.out.println("Dry run: nothing was written to PostgreSQL. 'Inserted' below is what would be loaded.");
            return report;
        }

        // Phase 2: write.
        try (Connection connection = DriverManager.getConnection(config.pgUrl, config.pgUser, config.pgPassword)) {
            connection.setAutoCommit(false);
            PostgresWriter writer = new PostgresWriter(connection);

            try {
                writer.truncateAll();

                for (ObjectType type : WRITE_ORDER) {
                    Set<String> ids = accepted.getOrDefault(type.name(), Set.of());
                    if (ids.isEmpty()) {
                        continue;
                    }
                    Map<String, String> commentAnswers = new HashMap<>();
                    int[] inserted = {0};
                    couchDb.forEachPage(page -> {
                        List<JsonNode> batch = page.stream()
                                .filter(doc -> type.name().equals(doc.path(FieldMappings.TYPE).asText(null))
                                        && ids.contains(doc.path(FieldMappings.ID).asText()))
                                .toList();
                        if (batch.isEmpty()) {
                            return;
                        }
                        inserted[0] += insert(writer, type, batch);
                        if (type == ObjectType.Comment) {
                            for (JsonNode doc : batch) {
                                JsonNode answerTo = doc.path(FieldMappings.Comment.ANSWER_TO);
                                if (!answerTo.isMissingNode() && !answerTo.isNull()) {
                                    commentAnswers.put(doc.path(FieldMappings.ID).asText(), answerTo.asText());
                                }
                            }
                        }
                    });
                    report.recordInserted(type.name(), inserted[0]);
                    if (type == ObjectType.Comment) {
                        // Second pass: a comment can answer one that came later in the stream.
                        writer.linkCommentAnswers(commentAnswers);
                    }
                }

                writer.backfillCreatedAt();

                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }

        return report;
    }

    private static int insert(PostgresWriter writer, ObjectType type, List<JsonNode> docs) throws SQLException {
        return switch (type) {
            case Advisory -> writer.insertAdvisories(docs);
            case AdvisoryVersion -> writer.insertAdvisoryVersions(docs);
            case Comment -> writer.insertComments(docs);
            case AuditTrailDocument -> writer.insertAuditTrailDocuments(docs);
            case AuditTrailWorkflow -> writer.insertAuditTrailWorkflows(docs);
            case CommentAuditTrail -> writer.insertAuditTrailComments(docs);
            case Counter -> writer.insertCounters(docs);
        };
    }

    /** @param trackingIdByAdvisoryId tracking id of every accepted advisory that has one */
    static void checkDuplicateTrackingIds(Map<String, String> trackingIdByAdvisoryId, MigrationReport report) {
        Map<String, String> firstSeen = new LinkedHashMap<>();
        trackingIdByAdvisoryId.forEach((advisoryId, trackingId) -> {
            String other = firstSeen.putIfAbsent(trackingId, advisoryId);
            if (other != null) {
                report.recordError(advisoryId, "duplicate tracking id '" + trackingId + "' (also on advisory " + other + ")");
            }
        });
    }
}
