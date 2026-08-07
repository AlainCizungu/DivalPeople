#!/bin/sh
# Takes one encrypted backup of everything, then prunes old ones.
#
# pg_dumpall rather than pg_dump: it captures both databases *and the roles*. Restoring a dump
# without the roles gives you a database whose tables are owned by accounts that do not exist, and
# dip_app — the unprivileged role the whole tenant boundary rests on — would be missing entirely.
#
# The output is encrypted to a public key. The private key is not on this machine and must not be.
# That is the point: a backup is a complete copy of every salary, national identifier and debt
# record in the system, sitting in one file, and whoever takes the host should not thereby get it.
set -eu

: "${PGHOST:?PGHOST must be set}"
: "${PGUSER:?PGUSER must be set}"
: "${PGPASSWORD:?PGPASSWORD must be set}"
: "${BACKUP_DIR:?BACKUP_DIR must be set}"
: "${BACKUP_KEEP:?BACKUP_KEEP must be set}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

log() {
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"
}

fail() {
    log "FAILED: $*"
    exit 1
}

# ISO-8601 in UTC, so the names sort lexically and pruning the oldest is a `sort | head`.
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

if [ "${BACKUP_ENCRYPTION_DISABLED:-false}" = "true" ]; then
    # Deliberately loud. There is a legitimate case — a destination that encrypts at rest under a
    # key you hold — and there is the far more common case of somebody who could not be bothered.
    log "WARNING: encryption is disabled. This file is a plaintext copy of every salary, national"
    log "WARNING: identifier and debt record in the system. Anyone who reads the disk reads all of it."
    suffix="sql.gz"
    encrypt() { cat; }
else
    : "${BACKUP_AGE_PUBLIC_KEY:?BACKUP_AGE_PUBLIC_KEY must be set, or set BACKUP_ENCRYPTION_DISABLED=true and read the warning it prints}"
    suffix="sql.gz.age"
    encrypt() { age -r "$BACKUP_AGE_PUBLIC_KEY"; }
fi

target="${BACKUP_DIR}/dip-${stamp}.${suffix}"
partial="${WORK}/archive"

mkdir -p "$BACKUP_DIR"
log "starting"

# The exit status of pg_dumpall, not of the last command in the pipeline.
#
# `a | b > out` reports only b's status, so gzip succeeding would mask pg_dumpall failing and
# produce a perfectly valid archive of nothing — the worst possible outcome, because it looks
# exactly like success until the day you need it. `set -o pipefail` would do this but is not POSIX,
# so the status is carried out of the subshell in a file.
(
    pg_dumpall --clean --if-exists 2>"${WORK}/dump.err"
    echo "$?" > "${WORK}/dump.rc"
) | gzip -9 | encrypt > "$partial"

rc="$(cat "${WORK}/dump.rc" 2>/dev/null || echo 1)"
if [ "$rc" -ne 0 ]; then
    fail "pg_dumpall exited ${rc}: $(head -c 2000 "${WORK}/dump.err" 2>/dev/null)"
fi

size="$(wc -c < "$partial")"
# An empty or trivially small archive means the dump produced nothing worth keeping. Better to
# fail now than to accumulate a directory of small files that all look like backups.
if [ "$size" -lt 1024 ]; then
    fail "archive is only ${size} bytes, which cannot be a real dump"
fi

# Moved into place only once it is known-good, so the backup directory never contains a truncated
# file. A directory you have to check file-by-file is one you will not check on the day it matters.
mv "$partial" "$target"
log "wrote $(basename "$target") (${size} bytes)"

# --- prune ------------------------------------------------------------------
count="$(find "$BACKUP_DIR" -maxdepth 1 -name "dip-*.${suffix}" -type f | wc -l)"
if [ "$count" -gt "$BACKUP_KEEP" ]; then
    find "$BACKUP_DIR" -maxdepth 1 -name "dip-*.${suffix}" -type f \
        | sort \
        | head -n "$((count - BACKUP_KEEP))" \
        | while read -r old; do
            log "pruning $(basename "$old")"
            rm -f "$old"
        done
fi

log "done; keeping ${BACKUP_KEEP}"

# A backup that is only on this host protects against exactly one failure — the database breaking
# while the machine survives — and not the common one. This is a reminder, not a solution: where
# the copy goes is a decision this script should not quietly make for you.
if [ "${BACKUP_OFFSITE_CONFIRMED:-false}" != "true" ]; then
    log "NOTE: nothing here copies this file off the host. See docs/DEPLOYMENT.md."
fi
