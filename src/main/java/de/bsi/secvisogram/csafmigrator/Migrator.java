package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsi.secvisogram.csafmigrator.mapping.FieldMappings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The actual read-validate-write pipeline, split out from {@link Main} so it can be exercised
 * directly from a test without going through CLI argument parsing or {@code System.exit}.
 *
 * <p>Documents are streamed, never held in full. Phase 1 reads the whole database once and keeps
 * only a small record per document (see {@link DocumentValidator}). Phase 2 then fetches each accepted
 * document once, by id, one document type after the other in foreign-key order, and inserts it chunk by
 * chunk.</p>
 */
public final class Migrator {

    /** Insert order matters for foreign keys: parents before the documents that reference them. */
    private static final List<ObjectType> WRITE_ORDER = List.of(
            ObjectType.Advisory,
            ObjectType.Counter,
            ObjectType.AdvisoryVersion,
            ObjectType.Comment,
            ObjectType.AuditTrailDocument,
            ObjectType.AuditTrailWorkflow,
            ObjectType.CommentAuditTrail);

    /** Advisories and their versions carry the large csaf bodies, so they are fetched in small chunks. */
    private static final int BODY_HEAVY_CHUNK = 50;

    private Migrator() {
    }

    public static MigrationReport migrate(MigrationConfig config) throws Exception {
        long startedAt = System.nanoTime();
        System.out.println("Reading documents from " + config.couchdbUrl + " ...");
        CouchDbClient couchDb = new CouchDbClient(config.couchdbUrl, config.couchdbUser, config.couchdbPassword,
                config.pageSize);

        // Phase 1: validate everything before touching Postgres. Malformed documents abort the run
        // unless --skip-malformed was given; duplicate tracking IDs always abort, since there is no
        // way to know which of the clashing advisories should win.
        MigrationReport report = new MigrationReport();
        long documentCount = couchDb.documentCount();
        System.out.println("Phase 1 of 2: validating " + Progress.count(documentCount) + " documents.");
        DocumentValidator validator = readAndValidate(couchDb, config, report,
                new Progress("Validated", documentCount, !config.quiet));
        System.out.println("Read " + validator.documentCount() + " documents (validation pass took "
                + secondsSince(startedAt) + ").");
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

        // Phase 2: write. created_at of advisories and comments is inserted directly, from the earliest
        // audit-trail time the validation pass saw, and updated_at of advisories from the latest, so no slow
        // UPDATE of the rows is needed afterwards.
        System.out.println("Phase 2 of 2: writing to PostgreSQL.");
        Map<String, Instant> advisoryCreatedAt = validator.advisoryCreatedAt();
        Map<String, Instant> advisoryUpdatedAt = validator.advisoryUpdatedAt();
        Map<String, Instant> commentCreatedAt = validator.commentCreatedAt();
        try (Connection connection = DriverManager.getConnection(config.pgUrl, connectionProperties(config))) {
            connection.setAutoCommit(false);
            PostgresWriter writer = new PostgresWriter(connection);

            try {
                long stepStartedAt = System.nanoTime();
                writer.truncateAll();
                System.out.println("Truncated the target tables (" + secondsSince(stepStartedAt) + ").");

                for (ObjectType type : WRITE_ORDER) {
                    Set<String> acceptedIds = accepted.getOrDefault(type.name(), Set.of());
                    if (acceptedIds.isEmpty()) {
                        continue;
                    }
                    long passStartedAt = System.nanoTime();
                    System.out.println(type + ": loading " + Progress.count(acceptedIds.size()) + " documents.");
                    Progress progress = new Progress("Inserted", acceptedIds.size(), !config.quiet);
                    // Fetch exactly the accepted documents of this type, by id: each document is downloaded
                    // once instead of once per pass. Sorted ids keep neighbouring documents together.
                    List<String> ids = new ArrayList<>(acceptedIds);
                    Collections.sort(ids);
                    int[] inserted = {0};
                    Map<String, String> commentAnswers = new HashMap<>();

                    couchDb.forEachByIds(ids, chunkSize(type, config.pageSize), page -> {
                        // A document whose type changed since validation is not ours to insert.
                        List<JsonNode> batch = page.stream()
                                .filter(doc -> type.name().equals(doc.path(FieldMappings.TYPE).asText(null)))
                                .toList();
                        if (batch.isEmpty()) {
                            return;
                        }
                        inserted[0] += insert(writer, type, batch, advisoryCreatedAt, advisoryUpdatedAt, commentCreatedAt);
                        progress.advance(batch.size());
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
                    System.out.println("Inserted " + inserted[0] + " " + type + " (took "
                            + secondsSince(passStartedAt) + ").");
                    if (type == ObjectType.Comment) {
                        // Second step: a comment can answer one that was inserted later in the same pass.
                        long linkStartedAt = System.nanoTime();
                        int linked = writer.linkCommentAnswers(commentAnswers);
                        System.out.println("Linked " + linked + " comment answers (" + secondsSince(linkStartedAt) + ").");
                    }
                }

                long commitStartedAt = System.nanoTime();
                connection.commit();
                System.out.println("Committed (commit took " + secondsSince(commitStartedAt) + "). Total time "
                        + secondsSince(startedAt) + ".");
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

    /**
     * Credentials plus {@code reWriteBatchedInserts}, which lets the driver turn a JDBC batch into
     * multi-row INSERTs and so saves round trips. Not added when the URL already sets it.
     */
    private static Properties connectionProperties(MigrationConfig config) {
        Properties properties = new Properties();
        properties.setProperty("user", config.pgUser);
        properties.setProperty("password", config.pgPassword);
        if (!config.pgUrl.contains("reWriteBatchedInserts")) {
            properties.setProperty("reWriteBatchedInserts", "true");
        }
        return properties;
    }

    /**
     * Phase 1. By default reads only a small projection of the required fields of each document, which
     * avoids downloading the large csaf bodies; falls back to reading whole documents if this CouchDB
     * cannot run the query.
     */
    private static DocumentValidator readAndValidate(CouchDbClient couchDb, MigrationConfig config,
                                                     MigrationReport report, Progress progress) throws Exception {
        if (!config.fullValidationRead) {
            DocumentValidator validator = new DocumentValidator(report, true);
            // Advisories and versions whose csaf the projection cannot vouch for are rare; they are collected
            // and checked directly, by id, in small batches.
            List<JsonNode> needCheck = new ArrayList<>();
            long[] checked = {0};
            boolean[] started = {false};
            long checkStartedAt = System.nanoTime();
            try {
                couchDb.forEachProjectedDocument(doc -> {
                    started[0] = true;
                    if (validator.needsCsafCheck(doc)) {
                        needCheck.add(doc);
                        if (needCheck.size() >= config.pageSize) {
                            checked[0] += checkCsaf(couchDb, validator, needCheck);
                        }
                    } else {
                        validator.accept(doc);
                    }
                    progress.advance(1);
                });
                checked[0] += checkCsaf(couchDb, validator, needCheck);
                if (checked[0] > 0) {
                    System.out.println("  Checked the csaf field of " + Progress.count(checked[0])
                            + " advisories and versions directly, by id.");
                }
                return validator;
            } catch (CouchDbClient.HttpStatusException e) {
                int status = e.status();
                if (started[0] || (status != 400 && status != 404 && status != 405 && status != 501)) {
                    throw e;
                }
                System.out.println("This CouchDB cannot run the projected read (HTTP " + status
                        + "); reading the whole documents instead of a projection of the required fields,"
                        + " which is much slower.");
            }
        }
        DocumentValidator validator = new DocumentValidator(report);
        couchDb.forEachDocument(doc -> {
            validator.accept(doc);
            progress.advance(1);
        });
        return validator;
    }

    /** Checks the csaf field of the collected documents directly and hands them to the validator. */
    private static int checkCsaf(CouchDbClient couchDb, DocumentValidator validator, List<JsonNode> needCheck)
            throws Exception {
        if (needCheck.isEmpty()) {
            return 0;
        }
        List<String> ids = needCheck.stream().map(doc -> doc.path(FieldMappings.ID).asText()).sorted().toList();
        validator.acceptChecked(needCheck, couchDb.findIdsWithCsafObject(ids));
        int count = needCheck.size();
        needCheck.clear();
        return count;
    }

    private static int chunkSize(ObjectType type, int pageSize) {
        return type == ObjectType.Advisory || type == ObjectType.AdvisoryVersion
                ? Math.min(pageSize, BODY_HEAVY_CHUNK) : pageSize;
    }

    private static String secondsSince(long startedAtNanos) {
        return Progress.elapsed((System.nanoTime() - startedAtNanos) / 1_000_000_000.0);
    }

    private static int insert(PostgresWriter writer, ObjectType type, List<JsonNode> docs,
                              Map<String, Instant> advisoryCreatedAt, Map<String, Instant> advisoryUpdatedAt,
                              Map<String, Instant> commentCreatedAt)
            throws SQLException {
        return switch (type) {
            case Advisory -> writer.insertAdvisories(docs, advisoryCreatedAt, advisoryUpdatedAt);
            case AdvisoryVersion -> writer.insertAdvisoryVersions(docs);
            case Comment -> writer.insertComments(docs, commentCreatedAt);
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
