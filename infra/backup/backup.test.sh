#!/bin/sh
# Tests backup.sh without a database.
#
# The behaviour worth proving is what happens when the dump *fails*, and that needs no PostgreSQL
# — only a pg_dumpall that misbehaves on demand. A backup script is only ever exercised in anger,
# so the failure paths are the ones that must be known-good, and they are exactly the paths a
# manual "does it produce a file?" check never reaches.
#
#   sh infra/backup/backup.test.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${HERE}/backup.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

PASSED=0
FAILED=0

pass() { PASSED=$((PASSED + 1)); echo "  ok   — $1"; }
bad()  { FAILED=$((FAILED + 1)); echo "  FAIL — $1"; }

# --- stubs ------------------------------------------------------------------
mkdir -p "${WORK}/bin"

# Stands in for pg_dumpall. STUB_MODE decides how it behaves.
cat > "${WORK}/bin/pg_dumpall" <<'STUB'
#!/bin/sh
case "${STUB_MODE:-ok}" in
    ok)      i=0; while [ $i -lt 500 ]; do echo "-- CREATE TABLE line $i with enough text to compress"; i=$((i+1)); done ;;
    empty)   : ;;
    fail)    echo "FATAL: password authentication failed" >&2; exit 1 ;;
    partial) echo "-- some output"; echo "FATAL: connection lost" >&2; exit 2 ;;
esac
STUB

# Stands in for age, so the test needs no key material.
cat > "${WORK}/bin/age" <<'STUB'
#!/bin/sh
printf 'age-encrypted:'
cat
STUB

chmod +x "${WORK}/bin/pg_dumpall" "${WORK}/bin/age"
PATH="${WORK}/bin:${PATH}"
export PATH

run_backup() {
    env PGHOST=stub PGUSER=stub PGPASSWORD=stub \
        BACKUP_DIR="$1" BACKUP_KEEP="${2:-7}" \
        BACKUP_AGE_PUBLIC_KEY=age1stubstubstub \
        STUB_MODE="${3:-ok}" \
        sh "$SCRIPT" > "${WORK}/out.log" 2>&1
}

archives() {
    find "$1" -maxdepth 1 -type f -name 'dip-*' 2>/dev/null | wc -l | tr -d ' '
}

echo "backup.sh"

# --- a good run -------------------------------------------------------------
DIR="${WORK}/good"; mkdir -p "$DIR"
if run_backup "$DIR"; then
    [ "$(archives "$DIR")" = "1" ] && pass "a successful dump leaves exactly one archive" \
        || bad "expected one archive, found $(archives "$DIR")"
    find "$DIR" -name '*.sql.gz.age' | grep -q . \
        && pass "the archive is named as encrypted" || bad "wrong archive name"
    head -c 14 "$(find "$DIR" -type f | head -1)" | grep -q 'age-encrypted' \
        && pass "the archive actually went through the encryption step" || bad "not encrypted"
else
    bad "a good run should succeed"
fi

# --- the case that matters --------------------------------------------------
DIR="${WORK}/failed"; mkdir -p "$DIR"
if run_backup "$DIR" 7 fail; then
    bad "a failing pg_dumpall must not be reported as success"
else
    pass "a failing pg_dumpall fails the script"
fi
[ "$(archives "$DIR")" = "0" ] \
    && pass "a failed dump leaves no archive behind" \
    || bad "a failed dump left $(archives "$DIR") file(s) that look like backups"

# --- failing halfway through, which is worse than failing at the start ------
DIR="${WORK}/partial"; mkdir -p "$DIR"
if run_backup "$DIR" 7 partial; then
    bad "a dump that dies mid-stream must not be reported as success"
else
    pass "a dump that dies mid-stream fails the script"
fi
[ "$(archives "$DIR")" = "0" ] \
    && pass "a truncated dump leaves no archive behind" \
    || bad "a truncated dump was kept as though it were a backup"

# --- an empty dump ----------------------------------------------------------
DIR="${WORK}/empty"; mkdir -p "$DIR"
if run_backup "$DIR" 7 empty; then
    bad "an empty dump must not be reported as success"
else
    pass "an empty dump is refused rather than kept"
fi
[ "$(archives "$DIR")" = "0" ] || bad "an empty dump left a file"

# --- retention --------------------------------------------------------------
DIR="${WORK}/prune"; mkdir -p "$DIR"
i=0
while [ $i -lt 5 ]; do
    run_backup "$DIR" 3 ok
    # Distinct timestamps: the names carry seconds, and pruning sorts by name.
    sleep 1
    i=$((i + 1))
done
[ "$(archives "$DIR")" = "3" ] \
    && pass "retention keeps exactly BACKUP_KEEP archives" \
    || bad "expected 3 archives after pruning, found $(archives "$DIR")"

# The newest must survive. Pruning the wrong end is a mistake that looks like it works.
newest="$(find "$DIR" -type f | sort | tail -1)"
[ -f "$newest" ] && pass "the newest archive is the one kept" || bad "newest archive was pruned"

# --- refusing to run unconfigured -------------------------------------------
DIR="${WORK}/nokey"; mkdir -p "$DIR"
if env PGHOST=stub PGUSER=stub PGPASSWORD=stub BACKUP_DIR="$DIR" BACKUP_KEEP=7 \
        sh "$SCRIPT" > "${WORK}/nokey.log" 2>&1; then
    bad "must not back up unencrypted just because no key was configured"
else
    pass "no encryption key and no explicit opt-out is refused"
fi

echo
echo "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ]
