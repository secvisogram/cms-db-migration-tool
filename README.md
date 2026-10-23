# CSAF CouchDB → PostgreSQL Migrator

One-shot tool that copies all data of a `csaf-cms-backend` installation from CouchDB (releases up to
`v1.1.6`) into the PostgreSQL schema used by later releases (2.0.0 and newer). It reads CouchDB over
plain HTTP and batch-inserts everything into PostgreSQL in a single transaction.

## Upgrading a csaf-cms-backend installation? Start here

**Follow the upgrade guide: [docs/upgrade-from-couchdb.md](docs/upgrade-from-couchdb.md).** You normally
do not run the tool by hand. The guide uses the [`compose.yaml`](compose.yaml) of this repository, which

- starts a temporary CouchDB on a **copy** of your old data (your original data is never opened),
- creates the new database schema in your PostgreSQL if it is not there yet,
- runs the tool as a container: first a dry run that changes nothing, then the real migration.

All you need is Docker and the two files `compose.yaml` and `.env.example`. No Java, nothing to build.

If you use your own compose file, no Docker, or the plain jar, follow
[docs/upgrade-from-couchdb-custom-setup.md](docs/upgrade-from-couchdb-custom-setup.md) instead.

Everything below is reference: how to run the tool by hand and how it works.

## Running the tool by hand (reference)

For the custom setup guide, for tests, or if you know what you are doing. In an upgrade, the guide above
does these steps for you.

### Get the tool

Pick one:

- **Jar** (Java 21): download `csaf-couchdb-postgres-migrator.jar` from the GitHub release, or build it
  with `mvn package` (result: `target/csaf-couchdb-postgres-migrator.jar`).
- **Docker image**: `ghcr.io/secvisogram/cms-db-migration-tool`

### Usage

```bash
java -jar csaf-couchdb-postgres-migrator.jar \
  --couchdb-url=https://old-host:5984/csaf \
  --couchdb-user=admin \
  --couchdb-password=admin \
  --pg-url=jdbc:postgresql://new-host:5432/csaf \
  --pg-user=csaf \
  --pg-password=secret
```

With Docker, the same arguments are appended to the image:

```bash
docker run --rm ghcr.io/secvisogram/cms-db-migration-tool --dry-run \
  --couchdb-url=... --couchdb-user=... --couchdb-password=...
```

| Option | Meaning |
|---|---|
| `--couchdb-url`, `--couchdb-user`, `--couchdb-password` | Source CouchDB database |
| `--pg-url`, `--pg-user`, `--pg-password` | Target PostgreSQL database (not needed with `--dry-run`) |
| `--dry-run` | Read CouchDB and run all validation, never touch PostgreSQL. **Run this first.** |
| `--skip-malformed` | Load the valid documents and list the malformed ones instead of aborting |
| `--page-size=N` | Documents read from CouchDB per request (default 500). Lower it for large documents if memory is tight. |
| `--full-validation-read` | Validate by reading the whole documents instead of a small projection of the required fields (much slower on large advisories; only for comparison) |
| `--quiet` | Leave out the progress lines printed every 15 seconds during the long steps (the phase and result lines stay) |

### Before you run it

- The target database must already have the `csaf-cms-backend` schema (its Flyway migration creates it; the
  `schema-init` service of [`compose.yaml`](compose.yaml) does that for you when you use it).
- **All target tables are truncated before loading.** Safe to re-run, but never point it at a database
  holding data you want to keep.
- Ideally run it against a copy of the old data, not the live CouchDB.

### Result

At the end it prints a source-count vs. inserted-count report per document type. A non-zero exit code
means a mismatch or error: **do not cut over**.

## What gets migrated

All seven document types of the old database: `Advisory`, `AdvisoryVersion`, `Comment`,
`CommentAuditTrail`, `AuditTrailDocument`, `AuditTrailWorkflow` and `Counter`.

- Document IDs (`_id`) are kept as primary keys. The two `Counter` documents keep their fixed labels.
- CouchDB `_rev` is dropped; the PostgreSQL `version` column starts at 0.
- `created_at` of advisories and comments is taken from their earliest audit-trail row. The old
  documents' own `createdAt` field is not used: it is missing on advisories that were edited at least
  once. Entities without audit rows, and all advisory versions, get the import time.
- The field mapping lives in `src/main/java/.../mapping/FieldMappings.java`.

## Validation

Everything is checked before anything is written: required fields, UUID and timestamp formats, unknown
or missing `type`, and foreign-key targets (versions, comments, answers and audit rows pointing at a
missing or rejected parent). By default, any problem aborts the run with nothing written and every
offending `_id` listed.

- Duplicate advisory tracking IDs always abort the run. Fix them in CouchDB and re-run.
- Column length limits (e.g. `VARCHAR(255)` owner) are not checked.

## Limitations

- No resume: a failure rolls back the whole transaction, and you re-run from scratch.
- Documents are streamed, so memory use depends on the document count, not their size. The tool reads
  a small projection of every document once to validate, then fetches each accepted document once by
  id, so CouchDB must not change during the run.
- Load tested with 481,746 documents (30,109 real Red Hat CSAF advisories plus generated versions, comments
  and audit rows): about 22 minutes, see [loadtest/README.md](loadtest/README.md).

## Development

```bash
mvn test      # end-to-end test with Testcontainers (CouchDB + PostgreSQL), needs Docker
mvn package   # build the shaded jar
```

`src/test/resources/V1__initial_schema.sql` is only a reference copy of the backend schema for the test.
In a real run the schema is owned by `csaf-cms-backend`.
