# Load test

Fills a throwaway CouchDB with a large dataset built from Red Hat's public CSAF advisories, runs the
migrator against a throwaway PostgreSQL, and reports time and heap use. It answers: how does the
migrator behave when the source database is big?

## Prerequisites

- Docker (the containers use host ports 15984 for CouchDB and 15432 for PostgreSQL)
- JDK 21 or newer on the `PATH`
- Maven, or an already built `target/csaf-couchdb-postgres-migrator.jar`
- Disk space: the advisories are cached in `loadtest/data/`, and CouchDB stores every copy of them.
  Check the size of `loadtest/data/` after the first download before raising `--copies`.

The Red Hat data is downloaded at run time from
`https://security.access.redhat.com/data/csaf/v2/advisories/` and is never committed (`loadtest/data/`
is git-ignored). Check Red Hat's terms before sharing anything derived from it.

## Getting the advisories (slow connection? use the archive)

Red Hat also publishes all advisories as one `csaf_advisories_<date>.tar.zst` (about 111 MB, with a
`.sha256` file) next to the advisory folders. One large file is much easier than about 30,000 small
requests on a slow connection. In order of precedence:

1. `--archive=FILE`: use an archive you downloaded yourself, wherever it is.
2. Any `csaf_advisories_*.zst` lying in `loadtest/data/` is used automatically (the newest one). Put the
   file there, and a `.sha256` file next to it if you have one: the checksum is then verified.
3. `--download-archive`: downloads the latest archive for you. The download is resumable
   (`curl -C -`), so after an interruption run the same command again. The file name comes from
   `archive_latest.txt` on the server.
4. `--offline`: use the `.json` files already unpacked in `loadtest/data/`.
5. Default: download the advisories one by one (cached in `loadtest/data/`).

An archive is unpacked once into `loadtest/data/archive/` by a short-lived `alpine` container that
installs `zstd` (this needs Docker and a one-time network access for the package, no tool on your
machine). Unpacking is skipped on later runs. To switch to a different archive, delete
`loadtest/data/archive/`.

## Run it

```bash
# Quick check: 200 advisories spread over all years, two heap sizes, two page sizes
loadtest/run.sh --limit=200 --xmx="256m 1g" --page-sizes="50 500"

# All advisories (about 30,000), then a bigger run with the set repeated 5 times
loadtest/run.sh --xmx="512m 1g 2g" --page-sizes="500"
loadtest/run.sh --reset --copies=5 --xmx="1g 2g 4g"

# Only validate (no PostgreSQL writes)
loadtest/run.sh --dry-run
```

| Option | Meaning |
|---|---|
| `--limit=N` | Use N advisories, spread evenly over the year-sorted index (default: all) |
| `--copies=K` | Repeat the set K times with new ids and tracking ids, to scale the data up |
| `--xmx="a b"` | Heap sizes to try, one migrator run each |
| `--page-sizes="a b"` | `--page-size` values to try |
| `--archive=FILE` | Use a `csaf_advisories_*.tar.zst` you already have (see above) |
| `--download-archive` | Download the latest archive, resumable, and verify its checksum |
| `--offline` | Use the advisories already unpacked in `loadtest/data/`, no download |
| `--reset` | Delete the containers and volumes first, so the data is seeded again |
| `--dry-run` | Run the migrator with `--dry-run` |

Seeding is skipped when the CouchDB database already has documents, so repeated runs only re-run the
migrator. Use `--reset` after changing `--limit` or `--copies`, which deletes the seeded data.

To keep several data sets, for example the full one and a smaller one, give each its own CouchDB database
with the environment variable `COUCH_DB` (default `csaf_load`) and run without `--reset`:

```bash
COUCH_DB=csaf_load_10k loadtest/run.sh --limit=10000 --xmx=1g
```

The PostgreSQL side is shared: every run truncates it first and loads the data set it is run with.

## What gets generated

Per advisory: the Red Hat document as `csaf`, 2 advisory versions, 3 comments (about 30% answering an
earlier comment), 4 document audit rows, 3 workflow audit rows and 1 audit row per comment, plus the two
counter documents. The ratios can be changed on the seeder, see
`java -cp target/csaf-couchdb-postgres-migrator.jar loadtest/LoadTestSeeder.java --help`.

## Reading the results

While the migrator runs, its output (also in `loadtest/results/run-<xmx>-<page>.log`, where
`tail -f` shows it live) has a phase header per step and a progress line every 15 seconds, for
example `Inserted: 3,500 of 30,109 (11.6%), 115/s, about 3 min 52 s left`. The rate and the
estimate are measured from the first document of each step, so the time spent on a preparation
before it does not distort them.

- The table printed at the end has one row per run: exit code, wall time and the highest heap
  occupancy after a garbage collection (a good proxy for the memory actually needed).
- `loadtest/results/run-<xmx>-<page>.log` has the migrator's own output, including the time of the
  validation pass and of each per-type pass.
- `loadtest/results/gc-<xmx>-<page>.log` is the GC log of the run.
- After the runs, `run.sh` compares the number of rows in PostgreSQL with the number of documents in
  CouchDB, and checks that the derived advisory columns are filled.

If the per-type passes dominate the total time, the next step would be a Mango `_find` query by type
(needs a `type` index on the CouchDB copy) or spilling the documents to temp files.

## Results

Run the migrator as a container on the Docker network (the default of `run.sh`). From the Windows host,
through the published ports, reading from CouchDB was measured at only 2.5 to 3 MB/s and did not speed
up with parallel readers, against 41 MB/s with one reader and about 145 MB/s with four inside the
network. Host numbers are therefore not comparable. PostgreSQL took about 5 ms per 126 KB advisory
(200 advisories/s) with the GIN index on `csaf`; without it about 310/s. That was a test with 600 advisories;
in the full migration the index cost much more, because its cost grows with its size (see the last two rows).
The backend's `V1__initial_schema.sql` no longer creates that index, so the last row is what the migration
costs with the current schema.

Fill in after running. Hardware, CouchDB and PostgreSQL versions: see `docker-compose.yml`.

| Date | Advisories | Documents | Xmx | Page size | Validation | Per-type passes | Total | Peak heap | Result |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-19 | 200 (smoke test, oldest advisories, 2.8 MB) | 3,202 | 256m | 500 | 2.9 s | 2.4 to 4.1 s each | 24 s | 10 MB | OK |
| 2026-10-19 | 30,109 (full Red Hat set, 55 over 7.5 MB skipped) | 481,746 | 1g | 500 | over 63 min, stopped unfinished (about 2 h estimated) | not measured | n/a | n/a | old code stopped by hand |
| 2026-10-19 | same | 481,746 | 1g | 500 | 270 s (projected read, `--dry-run`) | n/a | n/a | 142 MB | OK, all counts match |
| 2026-10-19 | same, migrator as a container in the Docker network | 481,746 | 1g | 500 | 243 s | 558 s + 460 s + 194 s (3 passes) | 1,904 s (31.7 min); about 450 s is outside the passes (truncate, answer links, `created_at` backfill, commit) | 488 MB | OK, all counts match, derived columns filled |
| 2026-10-19 | same, `created_at` set at insert time (no backfill) | 481,746 | 1g | 500 | 252 s | 559 s + 466 s + 201 s (3 passes) | 1,481 s (24.7 min); commit 0.3 s, answer links 2.1 s | 463 MB | OK, all counts match, `created_at` identical to the backfill run |
| 2026-10-20 | same, accepted documents fetched once by id in the write passes | 481,746 | 1g | 500 | 247 s | advisories 415 s, versions 398 s, comments 78 s, document audits 104 s, workflow audits 77 s, comment audits 83 s | 1,405 s (23.4 min) | 377 MB | OK, all counts match, `created_at` identical |
| 2026-10-20 | same, with progress lines | 481,746 | 1g | 500 | 250 s (of which the csaf-object check 108 s) | advisories 410 s, versions 379 s, comments 61 s, document audits 83 s, workflow audits 61 s, comment audits 60 s | 1,305 s (21.7 min) | 426 MB | OK, all counts match, `created_at` identical |
| 2026-10-20 | same, `--dry-run`, no whole-database check of the csaf field | 481,746 | 1g | 500 | 145 s | n/a | n/a | n/a | OK, same accepted counts as before |
| 2026-10-22 | same, GIN index `idx_advisories_csaf` present (the schema of the backend at that time) | 481,746 | 1g | 500 | 169 s | advisories 469 s, versions 399 s, comments 66 s, document audits 86 s, workflow audits 62 s, comment audits 62 s | 1,316 s (21 min 56 s) | 388 MB | OK, all counts match |
| 2026-10-22 | same, index dropped before the run (the schema of the backend now) | 481,746 | 1g | 500 | 147 s | advisories 213 s, versions 410 s, comments 64 s, document audits 86 s, workflow audits 65 s, comment audits 63 s | 1,052 s (17 min 32 s) | 380 MB | OK, all counts match |

The two runs differ only by the index. It exists on `advisories` only, so the versions (which also carry a
`csaf` document) do not change: the whole difference of 264 s is the advisory pass, 8.5 ms per advisory.
