package de.bsi.secvisogram.csafmigrator;

/**
 * The 7 document kinds that used to share a single CouchDB database, discriminated by
 * the "type" field. Copied verbatim from
 * {@code de.bsi.secvisogram.csaf_cms_backend.json.ObjectType} in csaf-cms-backend --
 * fork, don't depend on the main app's package, since that class (and the CouchDB era
 * it describes) is expected to disappear from csaf-cms-backend once phase 9 cleanup lands.
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
