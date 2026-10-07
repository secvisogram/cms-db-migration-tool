package de.bsi.secvisogram.csafmigrator;

/**
 * The 7 document kinds that shared a single CouchDB database in the CouchDB-based releases of
 * csaf-cms-backend (up to v1.1.6), discriminated by the "type" field. Copied from
 * {@code de.bsi.secvisogram.csaf_cms_backend.json.ObjectType} at that release. It is deliberately
 * a copy and not a dependency on the backend: this tool describes the old data, not the current
 * application.
 */
public enum ObjectType {
    Advisory,
    AdvisoryVersion,
    AuditTrailDocument,
    AuditTrailWorkflow,
    Comment,
    CommentAuditTrail,
    Counter
}
