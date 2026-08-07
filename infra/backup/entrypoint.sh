#!/bin/sh
# Runs backup.sh on a schedule.
#
# busybox crond rather than a sleep loop, so backups happen at a known time rather than drifting
# by however long the previous one took plus however long the container has been up.
#
# cron gives a job almost no environment, so the settings are written to a file the job sources.
# The alternative — putting them in the crontab line — puts the database password in a file that
# tools and shells read casually.
set -eu

: "${BACKUP_CRON:?BACKUP_CRON must be set}"

ENV_FILE=/etc/dip-backup.env

{
    echo "export PGHOST='${PGHOST}'"
    echo "export PGPORT='${PGPORT:-5432}'"
    echo "export PGUSER='${PGUSER}'"
    echo "export PGPASSWORD='${PGPASSWORD}'"
    echo "export BACKUP_DIR='${BACKUP_DIR}'"
    echo "export BACKUP_KEEP='${BACKUP_KEEP}'"
    echo "export BACKUP_AGE_PUBLIC_KEY='${BACKUP_AGE_PUBLIC_KEY:-}'"
    echo "export BACKUP_ENCRYPTION_DISABLED='${BACKUP_ENCRYPTION_DISABLED:-false}'"
    echo "export BACKUP_OFFSITE_CONFIRMED='${BACKUP_OFFSITE_CONFIRMED:-false}'"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

mkdir -p /etc/crontabs
echo "${BACKUP_CRON} . ${ENV_FILE} && /usr/local/bin/backup.sh >> /proc/1/fd/1 2>&1" \
    > /etc/crontabs/root

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: scheduled '${BACKUP_CRON}'"

# One backup at start-up, so a broken configuration is discovered now rather than at 3am. Failure
# here does not stop the container: an unbackupable database is a serious problem, but a restart
# loop on the backup service is not the way to report it.
if . "$ENV_FILE" && /usr/local/bin/backup.sh; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: start-up backup succeeded"
else
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: START-UP BACKUP FAILED — fix this now, the"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: scheduled ones will fail the same way"
fi

exec crond -f -l 8
