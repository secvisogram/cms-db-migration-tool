#!/usr/bin/env bash
# Load test for the migrator: seeds CouchDB with Red Hat CSAF advisories (see LoadTestSeeder.java),
# then runs the migrator for each combination of heap size and page size and prints a summary table.
#
#   loadtest/run.sh [--limit=N] [--copies=K] [--xmx="256m 1g"] [--page-sizes="50 500"] [--offline]
#                   [--archive=FILE | --download-archive] [--reset] [--dry-run] [--on-host]
#
# The migrator runs as a container on the same Docker network as CouchDB and PostgreSQL, like the real
# image does, using MIGRATOR_IMAGE (default eclipse-temurin:21-jre, the base of the Dockerfile). Set
# MIGRATOR_IMAGE to any local Java 21+ image to avoid a download. --on-host runs the jar on this machine
# instead, through the published ports; on Windows that path is more than ten times slower, so the
# numbers are not comparable.
#
# Source of the advisories, first match wins:
#   --archive=FILE      a csaf_advisories_*.tar.zst you downloaded yourself
#   (automatic)         a csaf_advisories_*.zst lying in loadtest/data/
#   --download-archive  download the latest archive (resumable, one file) and verify its checksum
#   --offline           use the .json files already in loadtest/data/
#   (default)           download the advisories one by one
# An archive is unpacked once into loadtest/data/archive/ using a short-lived Alpine container.
#
# Needs Docker, a JDK 21+ on the PATH and Maven (or an existing target/*.jar).
set -euo pipefail
cd "$(dirname "$0")/.."

LIMIT=0 COPIES=1 XMX="256m 1g" PAGE_SIZES="500" OFFLINE="" RESET=0 DRY_RUN="" ARCHIVE="" DOWNLOAD_ARCHIVE=0 ON_HOST=0
for arg in "$@"; do
  case "$arg" in
    --limit=*) LIMIT="${arg#*=}" ;;
    --copies=*) COPIES="${arg#*=}" ;;
    --xmx=*) XMX="${arg#*=}" ;;
    --page-sizes=*) PAGE_SIZES="${arg#*=}" ;;
    --offline) OFFLINE="--offline" ;;
    --archive=*) ARCHIVE="${arg#*=}" ;;
    --download-archive) DOWNLOAD_ARCHIVE=1 ;;
    --reset) RESET=1 ;;
    --on-host) ON_HOST=1 ;;
    --dry-run) DRY_RUN="--dry-run" ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

COMPOSE="docker compose -f loadtest/docker-compose.yml"
COUCH_BASE="http://127.0.0.1:15984"
# The CouchDB database to seed and migrate from. Use another name (COUCH_DB=csaf_load_10k loadtest/run.sh ...)
# to keep a second data set next to the first one in the same CouchDB volume.
COUCH_DB="${COUCH_DB:-csaf_load}"
COUCH_AUTH="admin:admin"
JAR="target/csaf-couchdb-postgres-migrator.jar"
RESULTS="loadtest/results"
NETWORK="loadtest_default"
MIGRATOR_IMAGE="${MIGRATOR_IMAGE:-eclipse-temurin:21-jre}"
mkdir -p "$RESULTS"

# Host path of a directory in a form Docker accepts on Windows (Git Bash) as well as on Linux and macOS.
abs_path() { (cd "$1" && { pwd -W 2>/dev/null || pwd; }); }

# The databases are local: never send these requests through an HTTP proxy from the environment.
curl_local() { curl --noproxy "*" -s --max-time 15 "$@"; }

if [ "$RESET" = 1 ]; then
  echo "== Dropping the load-test containers and volumes"
  $COMPOSE down -v
fi

echo "== Starting CouchDB and PostgreSQL"
$COMPOSE up -d

echo "== Waiting for the databases"
ready=0
for _ in $(seq 1 60); do
  if curl_local -f -u "$COUCH_AUTH" "$COUCH_BASE/" >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
if [ "$ready" != 1 ]; then
  echo "CouchDB did not answer at $COUCH_BASE within 2 minutes. Last attempt:" >&2
  curl_local -v -u "$COUCH_AUTH" "$COUCH_BASE/" >&2 || true
  echo "Container status:" >&2
  $COMPOSE ps >&2 || true
  exit 1
fi
ready=0
for _ in $(seq 1 60); do
  if $COMPOSE exec -T postgres pg_isready -U csaf -d csaf >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || { echo "PostgreSQL did not become ready within 2 minutes" >&2; exit 1; }

if [ ! -f "$JAR" ]; then
  echo "== Building the migrator jar"
  mvn -B -q package -DskipTests
fi

DATA="loadtest/data"
SOURCE_ARGS=""

# Turns a csaf_advisories_*.tar.zst into .json files below loadtest/data/archive/ (once).
prepare_archive() {
  local archive="$ARCHIVE" base="https://security.access.redhat.com/data/csaf/v2/advisories"
  mkdir -p "$DATA"
  if [ -z "$archive" ]; then
    archive="$(ls -t "$DATA"/csaf_advisories_*.zst 2>/dev/null | head -1 || true)"
  fi
  if [ -z "$archive" ] && [ "$DOWNLOAD_ARCHIVE" = 1 ]; then
    local name
    name="$(curl -sf --max-time 60 "$base/archive_latest.txt" | tr -d '
 ')"
    echo "== Downloading $name (resumable: run again after an interruption)"
    curl -L -C - -o "$DATA/$name" "$base/$name"
    curl -sf --max-time 60 -o "$DATA/$name.sha256" "$base/$name.sha256" || true
    archive="$DATA/$name"
  fi
  [ -n "$archive" ] || return 0
  [ -f "$archive" ] || { echo "Archive not found: $archive" >&2; exit 1; }
  # A zstd file starts with the bytes 28 b5 2f fd. Red Hat answers an unknown file name with an HTML page,
  # which a browser or curl would happily save under the archive's name.
  if [ "$(head -c 4 "$archive" | od -An -tx1 | tr -d ' \n')" != "28b52ffd" ]; then
    echo "$archive is not a zstd archive (an HTML error page?). Take the current file name from" >&2
    echo "$base/archive_latest.txt and download that one; old archives are removed from the server." >&2
    exit 1
  fi

  if [ -f "$archive.sha256" ]; then
    local expected actual
    expected="$(awk '{print $1}' "$archive.sha256")"
    actual="$(sha256sum "$archive" | awk '{print $1}')"
    [ "$expected" = "$actual" ] || { echo "Checksum mismatch for $archive (incomplete download?)" >&2; exit 1; }
    echo "== Checksum of $(basename "$archive") is OK"
  fi

  local marker="$DATA/archive/.extracted-$(basename "$archive")"
  if [ ! -f "$marker" ]; then
    echo "== Unpacking $(basename "$archive") into $DATA/archive (needs Docker and a one-time download of zstd for Alpine)"
    rm -rf "$DATA/archive"
    mkdir -p "$DATA/archive"
    # MSYS_NO_PATHCONV stops Git Bash from rewriting the container paths; pwd -W gives the Windows host path.
    local host_dir
    host_dir="$(cd "$(dirname "$archive")" && { pwd -W 2>/dev/null || pwd; })"
    MSYS_NO_PATHCONV=1 docker run --rm -v "$host_dir:/archives" -v "$(cd "$DATA/archive" && { pwd -W 2>/dev/null || pwd; }):/out"       alpine:3.20 sh -c "apk add --no-cache -q zstd tar && tar --zstd -xf '/archives/$(basename "$archive")' -C /out"
    touch "$marker"
  fi
  SOURCE_ARGS="--source-dir=$DATA/archive --offline"
}

doc_count() {
  curl_local -u "$COUCH_AUTH" "$COUCH_BASE/$COUCH_DB" | sed -n 's/.*"doc_count":\([0-9]*\).*/\1/p'
}

# The seeder writes this marker as its last step, so an interrupted seeding is picked up and finished
# on the next run (documents that are already there are ignored) instead of being mistaken for a complete one.
marker="$(curl_local -o /dev/null -w '%{http_code}' -u "$COUCH_AUTH" "$COUCH_BASE/$COUCH_DB/_local/loadtest-seed-complete" || true)"
if [ "$marker" = 200 ]; then
  echo "== CouchDB database $COUCH_DB is already seeded ($(doc_count) documents); skipping seeding (use --reset to reseed)"
else
  prepare_archive
  echo "== Seeding CouchDB (limit=$LIMIT, copies=$COPIES)"
  java -cp "$JAR" loadtest/LoadTestSeeder.java \
    --couchdb-url="$COUCH_BASE/$COUCH_DB" --couchdb-user=admin --couchdb-password=admin \
    --limit="$LIMIT" --copies="$COPIES" $OFFLINE $SOURCE_ARGS | tee "$RESULTS/seed.txt"
fi
SOURCE_DOCS="$(doc_count)"
echo "== Source database holds $SOURCE_DOCS documents"

printf '\n%-8s %-10s %-8s %-10s %-14s %s\n' "Xmx" "page-size" "exit" "time (s)" "peak heap (M)" "notes"
for xmx in $XMX; do
  for page in $PAGE_SIZES; do
    tag="xmx${xmx}-page${page}"
    start=$(date +%s)
    set +e
    if [ "$ON_HOST" = 1 ]; then
      java "-Xmx$xmx" "-Xlog:gc:file=$RESULTS/gc-$tag.log" -jar "$JAR" \
        --couchdb-url="$COUCH_BASE/$COUCH_DB" --couchdb-user=admin --couchdb-password=admin \
        --pg-url=jdbc:postgresql://127.0.0.1:15432/csaf --pg-user=csaf --pg-password=csaf \
        --page-size="$page" $DRY_RUN >"$RESULTS/run-$tag.log" 2>&1
    else
      # MSYS_NO_PATHCONV stops Git Bash from rewriting the container paths.
      MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
        -v "$(abs_path target):/app:ro" -v "$(abs_path "$RESULTS"):/results" "$MIGRATOR_IMAGE" \
        java "-Xmx$xmx" "-Xlog:gc:file=/results/gc-$tag.log" -jar "/app/$(basename "$JAR")" \
        --couchdb-url="http://couchdb:5984/$COUCH_DB" --couchdb-user=admin --couchdb-password=admin \
        --pg-url=jdbc:postgresql://postgres:5432/csaf --pg-user=csaf --pg-password=csaf \
        --page-size="$page" $DRY_RUN >"$RESULTS/run-$tag.log" 2>&1
    fi
    code=$?
    set -e
    elapsed=$(( $(date +%s) - start ))
    # Highest heap occupancy after a collection ("123M->45M(256M)": the 45M part).
    peak="$(grep -o '[0-9]*M->[0-9]*M' "$RESULTS/gc-$tag.log" 2>/dev/null | sed 's/.*->//; s/M//' | sort -n | tail -1 || true)"
    notes=""
    if grep -q OutOfMemoryError "$RESULTS/run-$tag.log"; then notes="OutOfMemoryError"; fi
    printf '%-8s %-10s %-8s %-10s %-14s %s\n' "$xmx" "$page" "$code" "$elapsed" "${peak:-?}" "$notes"
  done
done

if [ -z "$DRY_RUN" ]; then
  echo
  echo "== Checking the PostgreSQL result of the last run"
  psql() { $COMPOSE exec -T postgres psql -U csaf -d csaf -tAc "$1"; }
  total=0
  for table in advisories advisory_versions comments audit_trail_documents audit_trail_workflows audit_trail_comments counters; do
    n="$(psql "select count(*) from $table")"
    printf '  %-24s %s\n' "$table" "$n"
    total=$(( total + n ))
  done
  echo "  total rows $total, source documents $SOURCE_DOCS"
  if [ "$total" = "$SOURCE_DOCS" ]; then echo "  OK: counts match"; else echo "  MISMATCH"; fi
  missing="$(psql "select count(*) from advisories where tracking_id is null or document_title is null or current_release_date is null")"
  echo "  advisories with an empty tracking_id, document_title or current_release_date: $missing"
fi

echo
echo "Logs: $RESULTS/ (run-*.log has the migrator's phase timings, gc-*.log the heap)"
