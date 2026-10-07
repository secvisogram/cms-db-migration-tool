# csaf-couchdb-postgres-migrator

One-shot, standalone data migration tool for `csaf-cms-backend` issue #226 (CouchDB -> PostgreSQL).

Reads every document out of the source CouchDB database and batch-inserts it into the target
PostgreSQL schema (`V1__initial_schema.sql`, synced 2026-10-06 with branch `feat/couchdb-to-postgres-migration`).

## Why a separate project

The tool is its own project and not part of `csaf-cms-backend`. That means:
- `csaf-cms-backend` stays free of one-time migration code and the dependencies it would need.
- The tool is disposable -- delete it once the migration is done, there is nothing to clean up in the
  backend.
- It talks to CouchDB over plain HTTP (`_all_docs?include_docs=true`) with the JDK's own HTTP
  client, so no CouchDB client library is needed.

## Source data and field mapping

The tool reads the documents written by the CouchDB-based releases of `csaf-cms-backend` (up to and
including `v1.1.6`). All seven document types shared one database and are told apart by their `type`
field: `Advisory`, `AdvisoryVersion`, `Comment`, `CommentAuditTrail`, `AuditTrailDocument`,
`AuditTrailWorkflow` and `Counter`.

`src/main/java/.../mapping/FieldMappings.java` is the reference for how the CouchDB JSON field names
map to the PostgreSQL columns. The names come from the `couchdb/*Field.java` enums
(`AdvisoryField`, `CommentField`, `AuditTrailField`, `AdvisoryAuditTrailField`,
`CommentAuditTrailField`) and `json/TrackingIdCounter.java` of `csaf-cms-backend` at tag `v1.1.6`;
those classes no longer exist in later releases. If you migrate data from an older release, compare
against the same files at the matching tag, for example
`git show v1.1.0:src/main/java/de/bsi/secvisogram/csaf_cms_backend/couchdb/AdvisoryField.java`.

Document IDs (`_id`) are standard hyphenated UUIDs and are carried over unchanged as the primary
keys. The only exception are the two `Counter` documents: their `_id` is a fixed label
(`TMP_TRACKING_ID_COUNTER` or `FINAL_TRACKING_ID_COUNTER`) that becomes `counters.id` (a `VARCHAR`,
not a `UUID`). CouchDB's `_rev` has no counterpart and is dropped; the PostgreSQL `version` column
starts at 0.

## Building

```bash
mvn package
# -> target/csaf-couchdb-postgres-migrator.jar (shaded, all dependencies included)
```

## Running

Point it at the source CouchDB and an **empty or scratch** target Postgres database -- it
`TRUNCATE`s every target table before loading, so it's safe to re-run but not safe to point at a
database with data you want to keep:

```bash
java -jar target/csaf-couchdb-postgres-migrator.jar \
  --couchdb-url=https://old-host:5984/csaf \
  --couchdb-user=admin \
  --couchdb-password=admin \
  --pg-url=jdbc:postgresql://new-host:5432/csaf \
  --pg-user=csaf \
  --pg-password=secret
```

It prints a per-type source-count vs. inserted-count report at the end and exits non-zero on any
mismatch or error -- treat a non-clean exit as "do not cut over yet."

Options:

- `--dry-run` reads CouchDB and runs all validation, but never connects to PostgreSQL (the `--pg-*`
  arguments are then not needed). Run this first.
- `--skip-malformed` loads the valid documents and lists the malformed ones instead of aborting
  before anything is written. The exit code stays non-zero.

## Container image

The `Dockerfile` builds a runtime image (`ghcr.io/secvisogram/cms-db-migration-tool`, published by the
`Docker Image` workflow on release). The arguments above are simply appended to its entrypoint:

```bash
docker build -t cms-db-migration-tool .
docker run --rm cms-db-migration-tool --dry-run --couchdb-url=... --couchdb-user=... --couchdb-password=...
```

For the upgrade of a `csaf-cms-backend` installation this image is normally not run by hand: the
`migrate` profile in that project's `docker/compose.yaml` starts a temporary CouchDB on a copy of the old
data, runs the dry run or the real migration inside the compose network, and is described step by step in
`documents/upgrade-from-couchdb.md` of `csaf-cms-backend`.

The image build skips the tests (they need Docker); run `mvn test` separately.

## Testing

`src/test/java/.../MigrationIntegrationTest.java` is a real end-to-end check, not a mock-based
unit test: it starts a throwaway `couchdb:3.3.3` container and a throwaway `postgres:17-alpine`
container via Testcontainers, seeds the CouchDB side with one document of each of the 7
`ObjectType`s (including a comment that answers another comment, to exercise the two-pass FK
linking), applies `src/test/resources/V1__initial_schema.sql` to the Postgres side, runs
`Migrator.migrate(...)` against both, and asserts on the actual resulting rows -- counts per
table, the `advisory_versions.advisory_id` and `comments.answer_to` FK links, the `csaf` JSONB
round-trip, and the counter values.

```bash
mvn test
```

Requires Docker. Last verified 2026-08-14: all 9 seeded documents migrated, all assertions passed
(`Tests run: 1, Failures: 0, Errors: 0`).

`src/test/resources/V1__initial_schema.sql` is a **reference copy** of
`csaf-cms-backend`'s target schema, kept only so this test doesn't require checking out the main
repo. In a real migration run, the target database's schema is owned by `csaf-cms-backend`'s own
Flyway migration, not by this tool -- re-sync this copy from the source of truth if it ever
diverges, rather than treating it as authoritative.

## Known limitations

- **`created_at`** is backfilled for advisories and comments from their earliest audit-trail row.
  The source documents' own `createdAt` field is deliberately not used: the backend dropped it from
  an advisory on its first update, so it is not reliable. Entities with no audit rows, and all
  `advisory_versions`, keep the column default (import time).
- **Duplicate tracking IDs** among advisories are detected before anything is written (the target
  schema has a unique index on the tracking ID). The run aborts with a report listing the clashing
  advisories; fix them in CouchDB and re-run.
- **No retry/resume**: a failure partway through rolls back the whole transaction (one connection,
  one commit at the end). Fine for advisory-scale data; split into per-type transactions if a real
  dataset is large enough that re-running the whole thing on failure is expensive.
- **`_all_docs` pagination via `skip`/`limit`** gets slower as `skip` grows on very large
  databases. Switch to `startkey_docid`-based keyset pagination if that becomes a problem.
- **Malformed documents** are validated before anything is written: required fields, UUID and
  timestamp formats, unknown or missing `type`, and foreign-key targets (a version, comment, answer or
  audit row pointing at a missing or rejected parent is rejected too). By default any problem aborts the
  run with nothing written and every offending `_id` listed. With `--skip-malformed` the valid
  documents are loaded and the rest are listed in the report; the exit code stays non-zero either way.
  Duplicate tracking IDs always abort. Not checked: column length limits (e.g. `VARCHAR(255)` owner).
- **Verified only against small, hand-seeded data** (9 documents, one of each type). The happy
  path and the two FK-linking cases (advisory versions, comment answers) are covered; edge cases
  like malformed/legacy documents, very large datasets, or unusual field combinations from real
  production data are not -- run it against a copy of real data before trusting it for cutover.
