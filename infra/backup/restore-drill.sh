#!/bin/sh
# Restores a backup into a throwaway database and checks the data is really in it.
#
# A backup nobody has restored is not a backup, and a restore procedure written in a document is
# not a drill. This is the drill. It touches nothing that is running: it starts its own PostgreSQL
# on a random port, restores into that, asks it questions, and destroys it.
#
# Run it somewhere the PRIVATE key lives — your machine, not the server. That is the point of
# encrypting to a public key, and it means this cannot run unattended on the host. That is a
# feature: a restore drill you never watch tells you nothing.
#
#   sh infra/backup/restore-drill.sh /path/to/dip-20260806T023000Z.sql.gz.age ~/dip-backup.key
#
# Exits non-zero if the backup cannot be restored or looks empty.
set -eu

ARCHIVE="${1:-}"
KEYFILE="${2:-}"

if [ -z "$ARCHIVE" ]; then
    echo "usage: $0 <archive> [age-private-key-file]" >&2
    echo >&2
    echo "  <archive>  a dip-*.sql.gz.age file, or a plain dip-*.sql.gz if you disabled" >&2
    echo "             encryption and are living with that decision" >&2
    exit 2
fi

[ -f "$ARCHIVE" ] || { echo "no such file: $ARCHIVE" >&2; exit 2; }

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }

CONTAINER="dip-restore-drill-$$"
WORK="$(mktemp -d)"
PASSWORD="drill-$(date +%s)-$$"

cleanup() {
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

say() { echo "$(date -u +%H:%M:%S) drill: $*"; }

# --- decrypt ----------------------------------------------------------------
case "$ARCHIVE" in
    *.age)
        [ -n "$KEYFILE" ] || { echo "an encrypted archive needs the private key file" >&2; exit 2; }
        [ -f "$KEYFILE" ] || { echo "no such key file: $KEYFILE" >&2; exit 2; }
        command -v age >/dev/null 2>&1 || { echo "age is required to decrypt" >&2; exit 2; }
        say "decrypting"
        age -d -i "$KEYFILE" "$ARCHIVE" | gzip -d > "${WORK}/dump.sql"
        ;;
    *.gz)
        say "decompressing (this archive is not encrypted)"
        gzip -dc "$ARCHIVE" > "${WORK}/dump.sql"
        ;;
    *)
        echo "unrecognised archive: $ARCHIVE" >&2; exit 2 ;;
esac

lines="$(wc -l < "${WORK}/dump.sql")"
say "dump is ${lines} lines"
[ "$lines" -gt 100 ] || { echo "dump has almost nothing in it" >&2; exit 1; }

# --- restore into something disposable --------------------------------------
say "starting a throwaway PostgreSQL"
docker run -d --name "$CONTAINER" \
    -e POSTGRES_PASSWORD="$PASSWORD" \
    postgres:16.6-alpine >/dev/null

waited=0
until docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do
    waited=$((waited + 1))
    [ "$waited" -gt 60 ] && { echo "the scratch database never came up" >&2; exit 1; }
    sleep 1
done

say "restoring"
# ON_ERROR_STOP so a broken dump fails here rather than producing a half-restored database that
# answers some questions correctly. psql without it will cheerfully carry on past errors.
if ! docker exec -i -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
        psql -v ON_ERROR_STOP=1 -U postgres -d postgres < "${WORK}/dump.sql" > "${WORK}/restore.log" 2>&1; then
    echo "RESTORE FAILED" >&2
    tail -40 "${WORK}/restore.log" >&2
    exit 1
fi

# --- ask it questions -------------------------------------------------------
ask() {
    docker exec -e PGPASSWORD="$PASSWORD" "$CONTAINER" \
        psql -U postgres -d "$1" -tAc "$2" 2>/dev/null | tr -d ' \r'
}

failures=0
check() {
    label="$1"; actual="$2"; least="$3"
    if [ -n "$actual" ] && [ "$actual" -ge "$least" ] 2>/dev/null; then
        echo "  ok   — ${label}: ${actual}"
    else
        echo "  FAIL — ${label}: got '${actual}', expected at least ${least}"
        failures=$((failures + 1))
    fi
}

say "checking what came back"

# Everything below compares the restored database against THE ARCHIVE, not against the current
# source tree. That is the difference between a drill and a staleness check.
#
# The first version hardcoded "at least 17 migrations" and "at least 40 tables", and it failed on
# a perfectly good backup of a database that was one migration behind. It was answering "is this
# database up to date", which is a real question but somebody else's. The only question here is
# whether the archive restores faithfully — and a drill that cries wolf is a drill that gets
# ignored on the day it means something.

# pg_dumpall concatenates every database, separated by \connect lines. This pulls out just the
# section for `dip` so the counts describe one database rather than all of them.
# The name is compared as a field rather than matched in the pattern, because pg_dumpall may
# quote it and awk uses POSIX regex, which has no backreferences — a fact this got wrong once.
awk '/^\\connect /{ name=$2; gsub(/"/, "", name); inside=(name=="dip"); next } inside' \
    "${WORK}/dump.sql" > "${WORK}/dip.sql"

expected_tables="$(grep -c '^CREATE TABLE ' "${WORK}/dip.sql" || true)"
expected_policies="$(grep -c '^CREATE POLICY ' "${WORK}/dip.sql" || true)"

if [ "$expected_tables" -lt 1 ]; then
    echo "  FAIL — the archive contains no CREATE TABLE for the dip database at all" >&2
    exit 1
fi

check "tables restored, against $expected_tables in the archive" \
    "$(ask dip "select count(*) from information_schema.tables where table_schema='public'")" \
    "$expected_tables"

check "row-level security policies, against $expected_policies in the archive" \
    "$(ask dip "select count(*) from pg_policies where schemaname='public'")" \
    "$expected_policies"

# The roles matter as much as the rows. dip_app is the account the entire tenant boundary rests
# on, and a dump restored without it leaves an application that cannot connect and policies that
# refer to nothing. pg_dumpall carries roles; pg_dump would not have.
check "dip_app role restored" \
    "$(ask postgres "select count(*) from pg_roles where rolname='dip_app'")" 1

# Data actually came across, rather than an empty schema.
check "tenants" "$(ask dip "select count(*) from tenant")" 1

# Not a count — a correctness claim. A schema history containing a failure means the database this
# was taken from was already broken, and restoring it faithfully would restore the breakage.
failed="$(ask dip "select count(*) from flyway_schema_history where success = false")"
if [ "${failed:-1}" = "0" ]; then
    echo "  ok   — no failed migrations in the restored history"
else
    echo "  FAIL — the archive contains ${failed} failed migration(s)"
    failures=$((failures + 1))
fi

# Reported, not asserted. Whether the database was current is a different question from whether
# the backup is sound, and conflating them is what made the first version wrong.
say "for information: schema version $(ask dip "select max(version::numeric) from flyway_schema_history") \
across $(ask dip "select count(*) from flyway_schema_history") migrations"

echo
if [ "$failures" -eq 0 ]; then
    say "RESTORE DRILL PASSED — this archive is a real backup"
else
    say "RESTORE DRILL FAILED with ${failures} problem(s)"
    exit 1
fi
