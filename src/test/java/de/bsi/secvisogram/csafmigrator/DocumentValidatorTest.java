package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ADV = "11111111-1111-1111-1111-111111111111";
    private static final String ADV_BAD = "22222222-2222-2222-2222-222222222222";
    private static final String C1 = "33333333-3333-3333-3333-333333333333";
    private static final String C2 = "44444444-4444-4444-4444-444444444444";
    private static final String C3 = "55555555-5555-5555-5555-555555555555";
    private static final String OTHER = "66666666-6666-6666-6666-666666666666";

    private final Map<String, List<JsonNode>> byType = new LinkedHashMap<>();

    private void add(String type, String json) throws Exception {
        byType.computeIfAbsent(type, t -> new ArrayList<>()).add(MAPPER.readTree(json));
    }

    private static String advisory(String id, String extra) {
        return """
                {"_id": "%s", "type": "Advisory", "workflowState": "Draft", "owner": "u",
                 "versioningType": "Semantic", "csaf": {"document": {}} %s}
                """.formatted(id, extra);
    }

    private static String comment(String id, String advisoryId, String answerTo) {
        String answer = answerTo == null ? "" : ", \"answerTo\": \"" + answerTo + "\"";
        return """
                {"_id": "%s", "type": "Comment", "advisoryId": "%s", "owner": "u", "commentText": "t" %s}
                """.formatted(id, advisoryId, answer);
    }

    private Map<String, Set<String>> validate(MigrationReport report) {
        DocumentValidator validator = new DocumentValidator(report);
        byType.values().forEach(docs -> docs.forEach(validator::accept));
        return validator.finish();
    }

    @Test
    void keepsTheEarliestAuditTimePerAdvisoryAndComment() throws Exception {
        String adv2 = "77777777-7777-7777-7777-777777777777";
        add("Advisory", advisory(ADV, ""));
        add("Advisory", advisory(adv2, ""));
        add("Comment", comment(C1, ADV, null));
        add("Comment", comment(C2, ADV, null));
        // Document and workflow audits of one advisory count together, in any order.
        add("AuditTrailDocument", audit("AuditTrailDocument", "88888888-0000-0000-0000-000000000001", "advisoryId", ADV, "2026-01-05T10:00:00Z"));
        add("AuditTrailWorkflow", audit("AuditTrailWorkflow", "88888888-0000-0000-0000-000000000002", "advisoryId", ADV, "2026-01-02T08:30:00Z"));
        add("AuditTrailDocument", audit("AuditTrailDocument", "88888888-0000-0000-0000-000000000003", "advisoryId", ADV, "2026-01-09T10:00:00Z"));
        // A row that fails its own checks is not inserted, so it must not count.
        add("AuditTrailDocument", audit("AuditTrailDocument", "88888888-0000-0000-0000-000000000004", "advisoryId", ADV, "yesterday"));
        add("CommentAuditTrail", audit("CommentAuditTrail", "88888888-0000-0000-0000-000000000005", "commentId", C1, "2026-02-03T00:00:00Z"));
        add("CommentAuditTrail", audit("CommentAuditTrail", "88888888-0000-0000-0000-000000000006", "commentId", C1, "2026-02-01T12:00:00Z"));

        MigrationReport report = new MigrationReport();
        DocumentValidator validator = new DocumentValidator(report);
        byType.values().forEach(docs -> docs.forEach(validator::accept));
        validator.finish();

        assertEquals(java.time.Instant.parse("2026-01-02T08:30:00Z"), validator.advisoryCreatedAt().get(ADV));
        // updated_at: the latest valid audit row of the advisory, document and workflow audits together.
        assertEquals(java.time.Instant.parse("2026-01-09T10:00:00Z"), validator.advisoryUpdatedAt().get(ADV));
        assertEquals(java.time.Instant.parse("2026-02-01T12:00:00Z"), validator.commentCreatedAt().get(C1));
        // Without audit rows there is no entry, so the database default (import time) applies.
        assertFalse(validator.advisoryCreatedAt().containsKey(adv2));
        assertFalse(validator.advisoryUpdatedAt().containsKey(adv2));
        assertFalse(validator.commentCreatedAt().containsKey(C2));
    }

    private static String audit(String type, String id, String parentField, String parentId, String createdAt) {
        return """
                {"_id": "%s", "type": "%s", "%s": "%s", "createdAt": "%s", "user": "u", "changeType": "Update"}
                """.formatted(id, type, parentField, parentId, createdAt);
    }

    @Test
    void acceptsWellFormedDocuments() throws Exception {
        add("Advisory", advisory(ADV, ""));
        add("Comment", comment(C1, ADV, null));
        add("Comment", comment(C2, ADV, C1));
        add("Counter", "{\"_id\": \"TMP_TRACKING_ID_COUNTER\", \"type\": \"Counter\", \"count\": 3}");

        MigrationReport report = new MigrationReport();
        Map<String, Set<String>> valid = validate(report);

        assertFalse(report.hasErrors());
        assertEquals(2, valid.get("Comment").size());
    }

    @Test
    void rejectsMissingRequiredFieldAndBadUuid() throws Exception {
        add("Advisory", advisory(ADV, ""));
        add("Advisory", "{\"_id\": \"" + ADV_BAD + "\", \"type\": \"Advisory\", \"workflowState\": \"Draft\","
                + " \"versioningType\": \"Semantic\", \"csaf\": {}}");
        add("Advisory", advisory("not-a-uuid", ""));
        add("Advisory", "{\"type\": \"Advisory\"}");

        MigrationReport report = new MigrationReport();
        Map<String, Set<String>> valid = validate(report);

        assertEquals(1, valid.get("Advisory").size());
        assertEquals(3, report.errorCount());
    }

    @Test
    void rejectionCascadesToDependentDocuments() throws Exception {
        add("Advisory", advisory(ADV, ""));
        // Rejected: no owner. Everything pointing at it must go too.
        add("Advisory", "{\"_id\": \"" + ADV_BAD + "\", \"type\": \"Advisory\", \"workflowState\": \"Draft\","
                + " \"versioningType\": \"Semantic\", \"csaf\": {}}");
        add("AdvisoryVersion", """
                {"_id": "%s", "type": "AdvisoryVersion", "advisoryReference": "%s", "workflowState": "Draft",
                 "owner": "u", "versioningType": "Semantic", "csaf": {}}
                """.formatted(OTHER, ADV_BAD));
        add("Comment", comment(C1, ADV_BAD, null));
        add("Comment", comment(C2, ADV, C1));    // answers a rejected comment
        add("Comment", comment(C3, ADV, C2));    // answers an answer to a rejected comment
        add("CommentAuditTrail", """
                {"_id": "%s", "type": "CommentAuditTrail", "commentId": "%s", "createdAt": "2026-01-01T00:00:00Z",
                 "user": "u", "changeType": "Create"}
                """.formatted(OTHER, C3));

        MigrationReport report = new MigrationReport();
        Map<String, Set<String>> valid = validate(report);

        assertEquals(1, valid.get("Advisory").size());
        assertTrue(valid.get("AdvisoryVersion").isEmpty());
        assertTrue(valid.get("Comment").isEmpty());
        assertTrue(valid.get("CommentAuditTrail").isEmpty());
        assertEquals(6, report.errorCount());
    }

    @Test
    void rejectsBadTimestampOrphanedAuditTrailUnknownTypeAndNonNumericCounter() throws Exception {
        add("Advisory", advisory(ADV, ""));
        add("AuditTrailDocument", """
                {"_id": "%s", "type": "AuditTrailDocument", "advisoryId": "%s", "createdAt": "yesterday",
                 "user": "u", "changeType": "Update"}
                """.formatted(OTHER, ADV));
        add("AuditTrailWorkflow", """
                {"_id": "%s", "type": "AuditTrailWorkflow", "advisoryId": "%s", "createdAt": "2026-01-01T00:00:00Z",
                 "user": "u", "changeType": "StateChanged"}
                """.formatted(C1, ADV_BAD));
        add("Mystery", "{\"_id\": \"x\", \"type\": \"Mystery\"}");
        add("Counter", "{\"_id\": \"TMP_TRACKING_ID_COUNTER\", \"type\": \"Counter\", \"count\": \"many\"}");

        MigrationReport report = new MigrationReport();
        Map<String, Set<String>> valid = validate(report);

        assertEquals(1, valid.get("Advisory").size());
        assertEquals(4, report.errorCount());
    }
}
