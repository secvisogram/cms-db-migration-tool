package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsi.secvisogram.csafmigrator.mapping.FieldMappings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Plain-JDBC writer for the V1__initial_schema.sql tables. No JPA/Hibernate: this tool runs once
 * and exits, so entity-manager machinery buys nothing but startup time and another dependency to
 * keep in sync with the main app's entity classes.
 *
 * <p>Insert order matters for foreign keys: advisories first, then everything that references
 * advisories, then comments' self-referential answer_to (a second pass, since a comment can
 * reference another comment inserted later in the same batch), then audit_trail_comments last.
 */
public class PostgresWriter {

    private final Connection connection;

    public PostgresWriter(Connection connection) {
        this.connection = connection;
    }

    /** Wipes every target table. Safe because this tool is meant to run against an empty or
     *  scratch Postgres database and reload it fully each time -- see README for why that's
     *  preferable to upsert/ON CONFLICT logic for a one-shot migration. */
    public void truncateAll() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    TRUNCATE TABLE audit_trail_comments,
                                   audit_trail_documents,
                                   audit_trail_workflows,
                                   advisory_versions,
                                   comments,
                                   advisories,
                                   counters
                    CASCADE
                    """);
        }
    }

    public int insertAdvisories(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO advisories
                    (id, workflow_state, owner, csaf, versioning_type, last_major_version, tmp_tracking_id)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setString(2, text(doc, FieldMappings.Advisory.WORKFLOW_STATE));
                ps.setString(3, text(doc, FieldMappings.Advisory.OWNER));
                ps.setString(4, doc.path(FieldMappings.Advisory.CSAF).toString());
                ps.setString(5, text(doc, FieldMappings.Advisory.VERSIONING_TYPE));
                ps.setString(6, text(doc, FieldMappings.Advisory.LAST_MAJOR_VERSION));
                ps.setString(7, text(doc, FieldMappings.Advisory.TMP_TRACKING_ID));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    public int insertAdvisoryVersions(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO advisory_versions
                    (id, advisory_id, workflow_state, owner, csaf, versioning_type, last_major_version)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setObject(2, uuidOrNull(text(doc, FieldMappings.Advisory.ADVISORY_REFERENCE)));
                ps.setString(3, text(doc, FieldMappings.Advisory.WORKFLOW_STATE));
                ps.setString(4, text(doc, FieldMappings.Advisory.OWNER));
                ps.setString(5, doc.path(FieldMappings.Advisory.CSAF).toString());
                ps.setString(6, text(doc, FieldMappings.Advisory.VERSIONING_TYPE));
                ps.setString(7, text(doc, FieldMappings.Advisory.LAST_MAJOR_VERSION));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    /** Pass 1: insert every comment with answer_to left NULL (a comment may reference another
     *  comment that hasn't been inserted yet). Call {@link #linkCommentAnswers} afterwards. */
    public int insertComments(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO comments
                    (id, advisory_id, owner, comment_text, csaf_node_id, field_name, answer_to)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setObject(2, uuid(doc, FieldMappings.Comment.ADVISORY_ID));
                ps.setString(3, text(doc, FieldMappings.Comment.OWNER));
                ps.setString(4, text(doc, FieldMappings.Comment.TEXT));
                ps.setString(5, text(doc, FieldMappings.Comment.CSAF_NODE_ID));
                ps.setString(6, text(doc, FieldMappings.Comment.FIELD_NAME));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    /** Pass 2: wire up answer_to now that every comment row exists. */
    public int linkCommentAnswers(List<JsonNode> docs) throws SQLException {
        String sql = "UPDATE comments SET answer_to = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                String answerTo = text(doc, FieldMappings.Comment.ANSWER_TO);
                if (answerTo == null) {
                    continue;
                }
                ps.setObject(1, UUID.fromString(answerTo));
                ps.setObject(2, uuid(doc));
                ps.addBatch();
                count++;
            }
            if (count > 0) {
                ps.executeBatch();
            }
            return count;
        }
    }

    public int insertAuditTrailDocuments(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO audit_trail_documents
                    (id, advisory_id, created_at, "user", change_type, diff, old_doc_version, doc_version)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setObject(2, uuid(doc, FieldMappings.AdvisoryAuditTrail.ADVISORY_ID));
                ps.setTimestamp(3, timestamp(doc, FieldMappings.AuditTrailCommon.CREATED_AT));
                ps.setString(4, text(doc, FieldMappings.AuditTrailCommon.USER));
                ps.setString(5, text(doc, FieldMappings.AuditTrailCommon.CHANGE_TYPE));
                JsonNode diff = doc.path(FieldMappings.AdvisoryAuditTrail.DIFF);
                ps.setString(6, diff.isMissingNode() || diff.isNull() ? "null" : diff.toString());
                ps.setString(7, text(doc, FieldMappings.AdvisoryAuditTrail.OLD_DOC_VERSION));
                ps.setString(8, text(doc, FieldMappings.AdvisoryAuditTrail.DOC_VERSION));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    public int insertAuditTrailWorkflows(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO audit_trail_workflows
                    (id, advisory_id, created_at, "user", change_type, old_state, new_state, old_doc_version, doc_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setObject(2, uuid(doc, FieldMappings.AdvisoryAuditTrail.ADVISORY_ID));
                ps.setTimestamp(3, timestamp(doc, FieldMappings.AuditTrailCommon.CREATED_AT));
                ps.setString(4, text(doc, FieldMappings.AuditTrailCommon.USER));
                ps.setString(5, text(doc, FieldMappings.AuditTrailCommon.CHANGE_TYPE));
                ps.setString(6, text(doc, FieldMappings.AdvisoryAuditTrail.OLD_WORKFLOW_STATE));
                ps.setString(7, text(doc, FieldMappings.AdvisoryAuditTrail.NEW_WORKFLOW_STATE));
                ps.setString(8, text(doc, FieldMappings.AdvisoryAuditTrail.OLD_DOC_VERSION));
                ps.setString(9, text(doc, FieldMappings.AdvisoryAuditTrail.DOC_VERSION));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    public int insertAuditTrailComments(List<JsonNode> docs) throws SQLException {
        String sql = """
                INSERT INTO audit_trail_comments
                    (id, comment_id, created_at, "user", change_type, comment_text)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setObject(1, uuid(doc));
                ps.setObject(2, uuid(doc, FieldMappings.CommentAuditTrail.COMMENT_ID));
                ps.setTimestamp(3, timestamp(doc, FieldMappings.AuditTrailCommon.CREATED_AT));
                ps.setString(4, text(doc, FieldMappings.AuditTrailCommon.USER));
                ps.setString(5, text(doc, FieldMappings.AuditTrailCommon.CHANGE_TYPE));
                ps.setString(6, text(doc, FieldMappings.CommentAuditTrail.COMMENT_TEXT));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    /**
     * CouchDB advisories and comments carry no creation timestamp of their own, so approximate
     * created_at with the earliest audit-trail entry for the entity. Entities without any audit
     * row keep the column default (import time), as do advisory_versions, which have no reliable
     * link to a specific audit row. Call after all inserts.
     */
    public void backfillCreatedAt() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    UPDATE advisories a SET created_at = t.first_seen
                    FROM (SELECT advisory_id, min(created_at) AS first_seen FROM (
                              SELECT advisory_id, created_at FROM audit_trail_documents
                              UNION ALL
                              SELECT advisory_id, created_at FROM audit_trail_workflows) x
                          GROUP BY advisory_id) t
                    WHERE a.id = t.advisory_id
                    """);
            stmt.execute("""
                    UPDATE comments c SET created_at = t.first_seen
                    FROM (SELECT comment_id, min(created_at) AS first_seen
                          FROM audit_trail_comments GROUP BY comment_id) t
                    WHERE c.id = t.comment_id
                    """);
        }
    }

    /** Counter docs are the one exception to "_id is a UUID" -- see FieldMappings.Counter. */
    public int insertCounters(List<JsonNode> docs) throws SQLException {
        String sql = "INSERT INTO counters (id, count) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (JsonNode doc : docs) {
                ps.setString(1, doc.path(FieldMappings.ID).asText());
                ps.setLong(2, doc.path(FieldMappings.Counter.COUNT).asLong(0));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
            return count;
        }
    }

    // -- field-extraction helpers --------------------------------------------------------------

    private static UUID uuid(JsonNode doc) {
        return UUID.fromString(doc.path(FieldMappings.ID).asText());
    }

    private static UUID uuid(JsonNode doc, String field) {
        return UUID.fromString(text(doc, field));
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String text(JsonNode doc, String field) {
        JsonNode value = doc.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Timestamp timestamp(JsonNode doc, String field) {
        String value = text(doc, field);
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }
}
