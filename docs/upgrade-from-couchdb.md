# Upgrading from the CouchDB release to the PostgreSQL release

> **Draft.** Written ahead of the **2.0.0** release of `csaf-cms-backend`: the tag
> `v2.0.0` and the backend image tag `2.0.0` exist only after that release.

Releases up to and including **1.1.6** of `csaf-cms-backend` store their data in
CouchDB. Starting with **2.0.0** the backend uses PostgreSQL. The REST API is
unchanged, but the **existing data has to be moved once**, with this tool. Nothing
to keep? Install fresh, no migration needed.

This guide assumes you run the stack from the `docker/` folder of a clone of
[csaf-cms-backend](https://github.com/secvisogram/csaf-cms-backend). For your own
compose file or a plain jar use the
[custom setup guide](upgrade-from-couchdb-custom-setup.md).

Two compose files are involved. This guide always calls them by these names:

- the **backend compose file**: `docker/compose.yaml` in your clone of
  `csaf-cms-backend`. It runs the CMS with PostgreSQL (and its other services). Run its
  commands in that clone's `docker/` folder.
- the **migration compose file**: [`compose.yaml`](../compose.yaml) in the root of
  *this* repository. It runs only the migration: a temporary CouchDB on a copy of your
  old data, and this tool. Run its commands in this repository's folder.

Both are used as plain `docker compose ...`, so check which folder you are in.

Your old data is only read, never changed: it is copied first and a temporary
CouchDB runs on the copy. Plan some downtime, because changes made after step 1 are
not migrated.

## 1 What you need

| Item | Notes |
|---|---|
| Downtime window | The old backend must be stopped while the data is copied. For typical data sizes the migration itself takes seconds to minutes (a load test with 481,746 documents took about 22 minutes). |
| This repository | Only the migration compose file ([`compose.yaml`](../compose.yaml)) and [`.env.example`](../.env.example) are needed. Clone it, or download the two files. |
| Docker | The new backend stack (backend compose file) runs PostgreSQL. The migration compose file starts a temporary CouchDB on a copy of your old data and runs the tool as a container (`ghcr.io/secvisogram/cms-db-migration-tool:1.0.3`). No Java needed. |
| Disk space | A temporary second copy of the old CouchDB data, plus the new PostgreSQL volume. |

## 2 Configuration

Two sets of settings are involved: the new PostgreSQL settings that the new backend
release needs (in the backend's `docker/.env`, for the backend compose file), and the
settings of the migration compose file (`.env` in this repository's folder, copied from
`.env.example`). **Keep the old CouchDB values until the
migration is verified.**

| Where | Variables | Used for |
|---|---|---|
| backend `docker/.env` | `CSAF_DB_NAME`, `CSAF_DB_USER`, `CSAF_DB_PASSWORD`, `CSAF_DB_PORT` | creating the new database; backend and migration log in with them |
| migration `.env` | `CSAF_COUCHDB_DATA_DIR`, `CSAF_COUCHDB_USER`, `CSAF_COUCHDB_PASSWORD`, `CSAF_COUCHDB_DBNAME` | opening your copy of the old data (defaults `admin` / `admin` / `csaf`) |
| migration `.env` | `CSAF_DB_HOST`, `CSAF_DB_PORT`, `CSAF_DB_NAME`, `CSAF_DB_USER`, `CSAF_DB_PASSWORD` | the target database; use the same values as in the backend's `docker/.env` |
| migration `.env` | `CSAF_BACKEND_IMAGE` | the image of the new backend, exactly the `image:` of `backend-cms` in the backend compose file. It is used only to create the database schema (see 3.4). |

`CSAF_DB_HOST` defaults to `host.docker.internal`, which reaches the PostgreSQL port
that the backend compose file publishes on the host (`CSAF_DB_PORT`). The PostgreSQL user
and password are **new credentials** chosen by you, not the CouchDB ones. Choose
them before the first start of the new stack: PostgreSQL creates its user only when
`cms-db` starts with an empty data volume, so changing `CSAF_DB_PASSWORD` later does
not change the existing database. If that happens, change the password back or
recreate the database (remove only the `cms-db-data` volume, not the Keycloak one).

`CSAF_COUCHDB_DATA_DIR`, `CSAF_BACKEND_IMAGE`, `CSAF_DB_NAME`, `CSAF_DB_USER` and
`CSAF_DB_PASSWORD` have no default and must be set in the migration `.env`. Compose checks
them for every command in this folder, so it refuses to start anything, the dry run and the
clean-up included, until they are all filled in.

**Host ports must not clash.** If another PostgreSQL already listens on
`CSAF_DB_PORT`, the tool may reach *that* server and fail with an authentication
error.

## 3 Upgrade procedure

### 3.1 Back up and stop, with the old version

1. If the old stack is running, make sure your checkout is still the **old**
   version (for example `git checkout v1.1.6`). Then stop it with the old backend
   compose file:

   ```shell
   cd docker
   docker compose down
   ```

   Do this *before* switching to the new version. The new version's backend compose
   file no longer knows the old CouchDB container.
2. Copy the CouchDB data folder (`docker/data/cms-db`) and your two `.env` files
   (`docker/.env` and the root `.env`) to a safe place. This is your backup.
   **Do not run `git clean`:** it deletes these untracked files.

### 3.2 Start the new version with an empty database

3. Check out the new version (commit or stash local changes first):
   `git fetch --tags` and `git checkout v2.0.0`. Adapt the `.env` files as in
   section 2, comparing them with the new `.env.example` files.
4. With the new backend compose file, start only its PostgreSQL service and wait until
   it is healthy. Do not start the backend yet, so that nothing writes during the
   migration. The empty schema is created by the migration itself (step 6), you do not
   have to start the backend for that.

   ```shell
   cd docker
   docker compose up -d --wait cms-db
   ```

   If you have already started the whole new stack, stop the backend again with
   `docker compose stop backend-cms`.

### 3.3 Dry run

5. With the migration compose file, in this repository's folder, with `.env` filled
   in (`CSAF_COUCHDB_DATA_DIR` points at the old data folder), validate the old data.
   It does not touch PostgreSQL:

   ```shell
   docker compose run --rm db-migration-dry-run
   ```

   The migration services copy the old data folder (read-only) into a scratch volume, start a
   temporary CouchDB on the copy, and validate every document. The report lists
   the number of documents per type and every document that cannot be migrated,
   with its `_id` and the reason. Fix the reported problems (see
   [Troubleshooting](#4-troubleshooting)) and repeat until it ends with
   `RESULT: all counts match.`

   The migration services belong to the compose profile `migrate`, so a plain
   `docker compose up` in this repository's folder starts nothing. They start only when
   named, as above.

### 3.4 Migrate

6. Run the migration, with the migration compose file in this repository's folder:

   ```shell
   docker compose run --rm db-migration
   ```

   First the service `schema-init` starts the image `CSAF_BACKEND_IMAGE` once, without
   web server, so that the backend's own Flyway migration creates the tables in the new
   database. It ends after a few seconds and changes nothing if the schema is already
   there. Compose shows nothing of its output; if it fails, see
   [Troubleshooting](#4-troubleshooting). The tool then empties the target tables and
   loads everything in **one transaction**
   (all or nothing). It prints progress every 15 seconds (`--quiet` turns that off)
   and source and inserted counts per type at the end. Any mismatch or error gives
   a non-zero exit code: do not continue, investigate first. It can be repeated
   safely; each run replaces the contents of the new database. Extra options can be
   appended, for example `docker compose run --rm db-migration --skip-malformed`.

### 3.5 Verify and go live

7. With the backend compose file, in the backend clone's `docker/` folder, start the
   backend again with `docker compose start backend-cms`, open the CMS in your browser (reload the
   page) and log in. Then check:
   - The advisory list shows the advisories you know, in the same workflow states.
     The number matches the `Advisory` line of the migration report.
   - Open one advisory you know well: its content (and its comments, if it had any)
     look as before.
   - Change something harmless in a test advisory and save. It saves without an
     error.
8. Clean up the scratch copy of the old data. With the migration compose file, in
   this repository's folder:

   ```shell
   docker compose down -v
   ```

   This only removes the migration's own containers and scratch volume (the project
   is named `csaf-cms-migration`). It does not touch the backend stack (the backend compose file). Then delete
   the old `CSAF_COUCHDB_*` lines. Keep your backup until you are confident.

### Alternative: run the tool as a jar

The tool is also a plain Java 21 program. Download
`csaf-couchdb-postgres-migrator.jar` from the
[release page](https://github.com/secvisogram/cms-db-migration-tool/releases) (it
shows the SHA-256 digest; compare it with `sha256sum`). Start a CouchDB of the same
major version on a **copy** of the old data yourself (CouchDB changes files when it
opens them, so never point it at your only backup), then follow the
[custom setup guide](upgrade-from-couchdb-custom-setup.md).

## 4 Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Report lists `duplicate tracking id '…'` and aborts | Two advisories share a tracking ID, which PostgreSQL does not allow. Change the tracking ID of one of them in the old system (or delete the duplicate), copy the data again and rerun. Duplicates are never skipped automatically because the tool cannot know which one is right. |
| Report lists malformed documents (missing owner, invalid UUID, comment pointing at a missing advisory, …) | Fix the data in CouchDB, or run with `--skip-malformed` to load all valid documents and list the others. The exit code stays non-zero. Documents that depend on a skipped document (versions, comments, audit entries) are skipped as well. |
| `required variable CSAF_DB_USER is missing a value` (or another `CSAF_...` name) | A required value is not set in the migration `.env`. Fill in `CSAF_COUCHDB_DATA_DIR` and the values of the backend's `docker/.env` for `CSAF_DB_NAME`, `CSAF_DB_USER` and `CSAF_DB_PASSWORD`. Compose checks them for every command, also for the dry run and `down`. |
| The dry run ends with `status 500 ... after 4 attempts`, `status 404 ... Database does not exist`, `status 401`, or finds no documents | `500`: `CSAF_COUCHDB_DATA_DIR` does not point at the folder with the CouchDB files, so the temporary CouchDB started empty. It must be the `cms-db` folder (`docker/data/cms-db` of the old checkout), not `docker/data`. `404`: wrong `CSAF_COUCHDB_DBNAME`. `401`: wrong `CSAF_COUCHDB_USER` or `CSAF_COUCHDB_PASSWORD`. |
| `password authentication failed` or connection refused on the real run | Another PostgreSQL listens on the same host port, or `CSAF_DB_HOST` / `CSAF_DB_PORT` is wrong. Check that the backend stack's `cms-db` is up and publishes `CSAF_DB_PORT`. |
| `host.docker.internal` does not resolve | Only possible on a Docker without `host-gateway` support (Docker older than 20.10). Set `CSAF_DB_HOST` to an address of the host. |
| `service "schema-init" didn't complete successfully` | The schema could not be created. Read why with `docker compose logs schema-init`. `FATAL: password authentication failed`: wrong `CSAF_DB_USER` or `CSAF_DB_PASSWORD`, or another PostgreSQL answers on that port. Connection refused: PostgreSQL is not running, or `CSAF_DB_HOST` or `CSAF_DB_PORT` is wrong. `Migration checksum mismatch for migration version 1` or `Found non-empty schema(s) "public" but no schema history table`: the database was not created by this backend release's Flyway migration (for example by an older version of it). Use a fresh, empty database, see the next row. The migration does not start in any of these cases. |
| Backend fails at startup with a Flyway checksum or "non-empty schema" error | The database was not created by this backend's Flyway migrations. Use a fresh, empty database (remove the `cms-db-data` volume of the new stack only, **not** the Keycloak volume) and start again. |
| Backend log shows connection attempts to CouchDB (`:5984`) | The old backend image is still in use. Check the image tag and rebuild or pull. |
| The tool runs out of memory | Documents are streamed, so memory depends on the document count, not their size. Use a smaller page size, for example `--page-size=100`, or raise the heap with `-e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=85`. |

## 5 What changes for users

- **Revisions.** The old CouchDB revision strings (`3-ab12…`) are replaced by plain
  counters (`0`, `1`, …), starting at `0` after the migration. Browser sessions that
  were open during the upgrade must be reloaded, otherwise saving fails with a
  wrong-revision error.
- **List order.** Advisories are returned ordered by their ID (the same as before).
- **IDs, versions, audit trails and comments** are kept.
- **Comments.** Comment migration (including answers) is covered by automated tests,
  but has so far not been verified on a real data set.

## Rollback

Until you delete it, your original data is untouched. To go back, stop the new
stack, start the old release with the original CouchDB data folder and your old
`.env` files. Changes made in the new system after the migration are not
transferred back.
