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
 * <p>Documents are fed in one at a time via {@link #accept}, so the full documents never have to be
 * held in memory: only a small record per valid document (its id and the ids it references) is
 * kept. {@link #finish} then runs the referential checks on those records.</p>
 *
 * <p>Referential checks cascade: a document pointing at an invalid or missing parent (an
 * AdvisoryVersion of a malformed Advisory, an answer to a skipped Comment, ...) is itself
 * rejected, since inserting it would violate a foreign key.</p>
 */
public final class DocumentValidator {

    /** What is kept of a document that passed its own-field checks. */
    private record Light(String id, String ref, String answerTo, String trackingId) {
    }

    private final MigrationReport report;
    private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
    private final Map<String, List<Light>> lights = new LinkedHashMap<>();
    private int documentCount;

    public DocumentValidator(MigrationReport report) {
        this.report = report;
    }

    /** Checks one document on its own and remembers what the referential checks need. */
    public void accept(JsonNode doc) {
        documentCount++;
        String type = doc.path(FieldMappings.TYPE).asText(null);
        if (type == null) {
            type = "<no type>";
        }
        sourceCounts.merge(type, 1, Integer::sum);

        if (!isKnownType(type)) {
            report.recordError(id(doc), "unknown document type '" + type + "'");
            return;
        }
        String problem = checkOwnFields(type, doc);
        if (problem != null) {
            report.recordError(id(doc), type + ": " + problem);
            return;
        }
        lights.computeIfAbsent(type, t -> new ArrayList<>()).add(light(ObjectType.valueOf(type), doc));
    }

    public int documentCount() {
        return documentCount;
    }

    /** Number of documents seen per type, including unknown types, in first-seen order. */
    public Map<String, Integer> sourceCounts() {
        return sourceCounts;
    }

    /**
     * Runs the referential checks. Every rejected document is recorded on the report with its
     * reason.
     *
     * @return the ids of the documents that are safe to insert, per known type present in the source
     */
    public Map<String, Set<String>> finish() {
        // Parents first. Comments iterate to a fixpoint because an answer can point at a comment
        // that is itself rejected.
        Set<String> advisoryIds = ids(ObjectType.Advisory);
        retain(ObjectType.AdvisoryVersion, FieldMappings.Advisory.ADVISORY_REFERENCE, Light::ref, advisoryIds);
        retain(ObjectType.Comment, FieldMappings.Comment.ADVISORY_ID, Light::ref, advisoryIds);
        int before;
        do {
            before = lights(ObjectType.Comment).size();
            Set<String> commentIds = ids(ObjectType.Comment);
            retain(ObjectType.Comment, FieldMappings.Comment.ANSWER_TO, Light::answerTo, commentIds);
        } while (lights(ObjectType.Comment).size() != before);

        retain(ObjectType.AuditTrailDocument, FieldMappings.AdvisoryAuditTrail.ADVISORY_ID, Light::ref, advisoryIds);
        retain(ObjectType.AuditTrailWorkflow, FieldMappings.AdvisoryAuditTrail.ADVISORY_ID, Light::ref, advisoryIds);
        retain(ObjectType.CommentAuditTrail, FieldMappings.CommentAuditTrail.COMMENT_ID, Light::ref, ids(ObjectType.Comment));

        Map<String, Set<String>> accepted = new LinkedHashMap<>();
        for (String type : sourceCounts.keySet()) {
            if (isKnownType(type)) {
                accepted.put(type, ids(ObjectType.valueOf(type)));
            }
        }
        return accepted;
    }

    /** Tracking id per accepted advisory that has a textual one. Call after {@link #finish}. */
    public Map<String, String> advisoryTrackingIds() {
        Map<String, String> trackingIds = new LinkedHashMap<>();
        for (Light advisory : lights(ObjectType.Advisory)) {
            if (advisory.trackingId() != null) {
                trackingIds.put(advisory.id(), advisory.trackingId());
            }
        }
        return trackingIds;
    }

    private static Light light(ObjectType type, JsonNode doc) {
        String id = doc.path(FieldMappings.ID).asText();
        return switch (type) {
            case Advisory -> {
                JsonNode trackingId = doc.path(FieldMappings.Advisory.CSAF).path("document").path("tracking").path("id");
                yield new Light(id, null, null, trackingId.isTextual() ? trackingId.asText() : null);
            }
            case AdvisoryVersion -> new Light(id, doc.path(FieldMappings.Advisory.ADVISORY_REFERENCE).asText(), null, null);
            case Comment -> {
                JsonNode answerTo = doc.path(FieldMappings.Comment.ANSWER_TO);
                yield new Light(id, doc.path(FieldMappings.Comment.ADVISORY_ID).asText(),
                        answerTo.isMissingNode() || answerTo.isNull() ? null : answerTo.asText(), null);
            }
            case AuditTrailDocument, AuditTrailWorkflow ->
                    new Light(id, doc.path(FieldMappings.AdvisoryAuditTrail.ADVISORY_ID).asText(), null, null);
            case CommentAuditTrail ->
                    new Light(id, doc.path(FieldMappings.CommentAuditTrail.COMMENT_ID).asText(), null, null);
            case Counter -> new Light(id, null, null, null);
        };
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

    private List<Light> lights(ObjectType type) {
        return lights.getOrDefault(type.name(), List.of());
    }

    /** Drops every document of {@code type} whose reference (a null reference is fine) is not in {@code knownIds}. */
    private void retain(ObjectType type, String field, Function<Light, String> referenceOf, Set<String> knownIds) {
        List<Light> docs = lights.get(type.name());
        if (docs == null) {
            return;
        }
        List<Light> kept = new ArrayList<>();
        for (Light doc : docs) {
            String reference = referenceOf.apply(doc);
            if (reference == null || knownIds.contains(reference)) {
                kept.add(doc);
            } else {
                report.recordError(doc.id(), type.name() + ": " + field + " '" + reference
                        + "' references a missing or rejected document");
            }
        }
        lights.put(type.name(), kept);
    }

    private Set<String> ids(ObjectType type) {
        Set<String> ids = new HashSet<>();
        lights(type).forEach(d -> ids.add(d.id()));
        return ids;
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
