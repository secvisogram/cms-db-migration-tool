package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsi.secvisogram.csafmigrator.mapping.FieldMappings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Checks every source document against what the target schema requires (NOT NULL columns, UUID
 * and timestamp formats, foreign-key targets) before anything is written, so a bad document is
 * reported with its id and reason instead of surfacing as a mid-run SQL exception.
 *
 * <p>Referential checks cascade: a document pointing at an invalid or missing parent (an
 * AdvisoryVersion of a malformed Advisory, an answer to a skipped Comment, ...) is itself
 * rejected, since inserting it would violate a foreign key.</p>
 */
public final class DocumentValidator {

    private DocumentValidator() {
    }

    /**
     * @return the documents that are safe to insert, grouped by type. Every rejected document is
     *         recorded on {@code report} with its reason.
     */
    public static Map<String, List<JsonNode>> validate(Map<String, List<JsonNode>> byType, MigrationReport report) {
        Map<String, List<JsonNode>> valid = new LinkedHashMap<>();

        for (Map.Entry<String, List<JsonNode>> entry : byType.entrySet()) {
            String type = entry.getKey();
            if (!isKnownType(type)) {
                entry.getValue().forEach(d -> report.recordError(id(d), "unknown document type '" + type + "'"));
                continue;
            }
            List<JsonNode> ok = new ArrayList<>();
            for (JsonNode doc : entry.getValue()) {
                String problem = checkOwnFields(type, doc);
                if (problem == null) {
                    ok.add(doc);
                } else {
                    report.recordError(id(doc), type + ": " + problem);
                }
            }
            valid.put(type, ok);
        }

        // Referential checks, parents first. Comments iterate to a fixpoint because an answer
        // can point at a comment that is itself rejected.
        Set<String> advisoryIds = ids(valid, ObjectType.Advisory);
        retainWhere(valid, ObjectType.AdvisoryVersion, report,
                d -> missing(FieldMappings.Advisory.ADVISORY_REFERENCE, d, advisoryIds));
        retainWhere(valid, ObjectType.Comment, report,
                d -> missing(FieldMappings.Comment.ADVISORY_ID, d, advisoryIds));
        int before;
        do {
            before = valid.getOrDefault(ObjectType.Comment.name(), List.of()).size();
            Set<String> commentIds = ids(valid, ObjectType.Comment);
            retainWhere(valid, ObjectType.Comment, report, d -> {
                JsonNode answerTo = d.path(FieldMappings.Comment.ANSWER_TO);
                return answerTo.isMissingNode() || answerTo.isNull()
                        ? null : missing(FieldMappings.Comment.ANSWER_TO, d, commentIds);
            });
        } while (valid.getOrDefault(ObjectType.Comment.name(), List.of()).size() != before);

        retainWhere(valid, ObjectType.AuditTrailDocument, report,
                d -> missing(FieldMappings.AdvisoryAuditTrail.ADVISORY_ID, d, advisoryIds));
        retainWhere(valid, ObjectType.AuditTrailWorkflow, report,
                d -> missing(FieldMappings.AdvisoryAuditTrail.ADVISORY_ID, d, advisoryIds));
        Set<String> commentIds = ids(valid, ObjectType.Comment);
        retainWhere(valid, ObjectType.CommentAuditTrail, report,
                d -> missing(FieldMappings.CommentAuditTrail.COMMENT_ID, d, commentIds));

        return valid;
    }

    private static String checkOwnFields(String type, JsonNode doc) {
        String id = doc.path(FieldMappings.ID).asText(null);
        if (id == null || id.isBlank()) {
            return "missing _id";
        }
        ObjectType objectType = ObjectType.valueOf(type);
        if (objectType != ObjectType.Counter && !isUuid(id)) {
            return "_id is not a UUID";
        }
        return switch (objectType) {
            case Advisory -> firstProblem(
                    requiredText(doc, FieldMappings.Advisory.WORKFLOW_STATE),
                    requiredText(doc, FieldMappings.Advisory.OWNER),
                    requiredText(doc, FieldMappings.Advisory.VERSIONING_TYPE),
                    requiredObject(doc, FieldMappings.Advisory.CSAF));
            case AdvisoryVersion -> firstProblem(
                    requiredText(doc, FieldMappings.Advisory.WORKFLOW_STATE),
                    requiredText(doc, FieldMappings.Advisory.OWNER),
                    requiredText(doc, FieldMappings.Advisory.VERSIONING_TYPE),
                    requiredObject(doc, FieldMappings.Advisory.CSAF),
                    requiredUuid(doc, FieldMappings.Advisory.ADVISORY_REFERENCE));
            case Comment -> firstProblem(
                    requiredText(doc, FieldMappings.Comment.OWNER),
                    requiredText(doc, FieldMappings.Comment.TEXT),
                    requiredUuid(doc, FieldMappings.Comment.ADVISORY_ID),
                    optionalUuid(doc, FieldMappings.Comment.ANSWER_TO));
            case AuditTrailDocument, AuditTrailWorkflow -> firstProblem(
                    requiredUuid(doc, FieldMappings.AdvisoryAuditTrail.ADVISORY_ID),
                    requiredTimestamp(doc, FieldMappings.AuditTrailCommon.CREATED_AT),
                    requiredText(doc, FieldMappings.AuditTrailCommon.USER),
                    requiredText(doc, FieldMappings.AuditTrailCommon.CHANGE_TYPE));
            case CommentAuditTrail -> firstProblem(
                    requiredUuid(doc, FieldMappings.CommentAuditTrail.COMMENT_ID),
                    requiredTimestamp(doc, FieldMappings.AuditTrailCommon.CREATED_AT),
                    requiredText(doc, FieldMappings.AuditTrailCommon.USER),
                    requiredText(doc, FieldMappings.AuditTrailCommon.CHANGE_TYPE));
            case Counter -> doc.path(FieldMappings.Counter.COUNT).isNumber() ? null : "'count' is not a number";
        };
    }

    // -- helpers ---------------------------------------------------------------------------------

    private static void retainWhere(Map<String, List<JsonNode>> valid, ObjectType type, MigrationReport report,
                                    Function<JsonNode, String> problemOf) {
        List<JsonNode> docs = valid.get(type.name());
        if (docs == null) {
            return;
        }
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode doc : docs) {
            String problem = problemOf.apply(doc);
            if (problem == null) {
                kept.add(doc);
            } else {
                report.recordError(id(doc), type.name() + ": " + problem);
            }
        }
        valid.put(type.name(), kept);
    }

    private static Set<String> ids(Map<String, List<JsonNode>> valid, ObjectType type) {
        Set<String> ids = new HashSet<>();
        valid.getOrDefault(type.name(), List.of()).forEach(d -> ids.add(d.path(FieldMappings.ID).asText()));
        return ids;
    }

    private static String missing(String field, JsonNode doc, Set<String> knownIds) {
        String value = doc.path(field).asText("");
        return knownIds.contains(value) ? null : field + " '" + value + "' references a missing or rejected document";
    }

    private static String firstProblem(String... problems) {
        for (String p : problems) {
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static String requiredText(JsonNode doc, String field) {
        JsonNode v = doc.path(field);
        return v.isTextual() && !v.asText().isBlank() ? null : "missing or empty '" + field + "'";
    }

    private static String requiredObject(JsonNode doc, String field) {
        return doc.path(field).isObject() ? null : "'" + field + "' is missing or not a JSON object";
    }

    private static String requiredUuid(JsonNode doc, String field) {
        JsonNode v = doc.path(field);
        return v.isTextual() && isUuid(v.asText()) ? null : "'" + field + "' is missing or not a UUID";
    }

    private static String optionalUuid(JsonNode doc, String field) {
        JsonNode v = doc.path(field);
        return v.isMissingNode() || v.isNull() ? null : requiredUuid(doc, field);
    }

    private static String requiredTimestamp(JsonNode doc, String field) {
        JsonNode v = doc.path(field);
        if (!v.isTextual()) {
            return "missing '" + field + "'";
        }
        try {
            Instant.parse(v.asText());
            return null;
        } catch (RuntimeException e) {
            return "'" + field + "' is not an ISO-8601 instant: " + v.asText();
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isKnownType(String type) {
        for (ObjectType t : ObjectType.values()) {
            if (t.name().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static String id(JsonNode doc) {
        return doc.path(FieldMappings.ID).asText("<no id>");
    }
}
