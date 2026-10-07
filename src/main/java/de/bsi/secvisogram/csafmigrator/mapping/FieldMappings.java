package de.bsi.secvisogram.csafmigrator.mapping;

/**
 * CouchDB JSON field names for each {@link de.bsi.secvisogram.csafmigrator.ObjectType}, transcribed
 * from the {@code couchdb/*Field.java} enums in csaf-cms-backend at tag v1.1.6 (the last
 * CouchDB-based release).
 *
 * <p>Those enums do not exist in later releases, so this is the reference for the original CouchDB
 * document shape. Do not "clean this up" to match the Postgres column names -- the whole point is
 * that the left-hand side is CouchDB's naming, not Postgres's.</p>
 *
 * <p>Common to every document (see the old {@code CouchDbField} enum): {@code _id}, {@code _rev},
 * {@code type}. Only {@code _id} and {@code type} matter here; {@code _rev} (the CouchDB optimistic-
 * concurrency token) has no target -- Postgres versioning starts fresh at {@code version = 0}.</p>
 */
public final class FieldMappings {

    private FieldMappings() {
    }

    public static final String ID = "_id";
    public static final String TYPE = "type";

    /** ObjectType.Advisory -- from {@code AdvisoryField}. Reused as-is for ObjectType.AdvisoryVersion,
     *  minus ADVISORY_REFERENCE/TMP_TRACKING_ID which only make sense on the live Advisory. */
    public static final class Advisory {
        private Advisory() {
        }

        public static final String WORKFLOW_STATE = "workflowState";
        public static final String OWNER = "owner";
        public static final String CSAF = "csaf";
        /** "Semantic" or "Integer". */
        public static final String VERSIONING_TYPE = "versioningType";
        public static final String LAST_MAJOR_VERSION = "lastMajorVersion";
        /** Only present on AdvisoryVersion docs: the source Advisory's _id. Maps to
         *  advisory_versions.advisory_id. */
        public static final String ADVISORY_REFERENCE = "advisoryReference";
        /** Only present on Advisory docs. */
        public static final String TMP_TRACKING_ID = "tmpTrackingId";
    }

    /** ObjectType.Comment -- from {@code CommentField}. */
    public static final class Comment {
        private Comment() {
        }

        public static final String TEXT = "commentText";
        public static final String OWNER = "owner";
        public static final String ADVISORY_ID = "advisoryId";
        public static final String CSAF_NODE_ID = "csafNodeId";
        public static final String FIELD_NAME = "fieldName";
        /** Self-reference to the parent comment's _id; null/absent for top-level comments. */
        public static final String ANSWER_TO = "answerTo";
    }

    /** Common to ObjectType.AuditTrailDocument, AuditTrailWorkflow and CommentAuditTrail --
     *  from {@code AuditTrailField}. */
    public static final class AuditTrailCommon {
        private AuditTrailCommon() {
        }

        public static final String CREATED_AT = "createdAt";
        public static final String USER = "user";
        public static final String CHANGE_TYPE = "changeType";
    }

    /** ObjectType.AuditTrailDocument and ObjectType.AuditTrailWorkflow both use this shape --
     *  from {@code AdvisoryAuditTrailField}. A document-change row populates DOC_VERSION/DIFF;
     *  a workflow-change row populates OLD_WORKFLOW_STATE/NEW_WORKFLOW_STATE. There is no separate
     *  discriminator field beyond the top-level "type" -- use that to decide which target table
     *  (audit_trail_documents vs audit_trail_workflows) a given doc goes to. */
    public static final class AdvisoryAuditTrail {
        private AdvisoryAuditTrail() {
        }

        public static final String ADVISORY_ID = "advisoryId";
        public static final String OLD_DOC_VERSION = "oldDocVersion";
        public static final String DOC_VERSION = "docVersion";
        /** RFC-6902 JSON Patch; AuditTrailDocument only. */
        public static final String DIFF = "diff";
        /** AuditTrailWorkflow only. */
        public static final String OLD_WORKFLOW_STATE = "oldState";
        /** AuditTrailWorkflow only. */
        public static final String NEW_WORKFLOW_STATE = "newState";
    }

    /** ObjectType.CommentAuditTrail -- from {@code CommentAuditTrailField}, plus AuditTrailCommon. */
    public static final class CommentAuditTrail {
        private CommentAuditTrail() {
        }

        public static final String COMMENT_ID = "commentId";
        public static final String COMMENT_TEXT = "commentText";
    }

    /**
     * ObjectType.Counter -- from {@code json.TrackingIdCounter}, not a couchdb/*Field enum.
     * Unlike every other type, the CouchDB {@code _id} is <b>not</b> a UUID -- it's one of two
     * fixed labels, {@code TMP_TRACKING_ID_COUNTER} or {@code FINAL_TRACKING_ID_COUNTER} -- and
     * that string is the row's primary key in Postgres too (counters.id is VARCHAR, not UUID).
     * Do not attempt UUID.fromString() on a Counter doc's _id.
     */
    public static final class Counter {
        private Counter() {
        }

        public static final String TMP_OBJECT_ID = "TMP_TRACKING_ID_COUNTER";
        public static final String FINAL_OBJECT_ID = "FINAL_TRACKING_ID_COUNTER";
        public static final String COUNT = "count";
    }
}
