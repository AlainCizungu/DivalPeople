#!/bin/sh
# Move the deployment to a new build. Run on the server, from the repository root.
#
#   sh infra/redeploy.sh <40-character commit sha>
#
# What it does, in order: takes a backup, pulls the new images while the old ones keep serving,
# swaps, waits for the frontend to answer, and prints the one line that puts it back.
#
# WHY A SCRIPT. The documented procedure is edit-pull-up, and it is four commands with a
# dangerous one in the middle. Done twice a week at a keyboard it is fine; done at eleven at
# night, in front of a partner who has found something, it is where somebody forgets the backup.
# The steps are not hard, they are just easy to leave out under exactly the conditions that make
# leaving them out expensive.
#
# WHAT IT DELIBERATELY DOES NOT DO: roll back on failure. Migrations run automatically at
# start-up and this project has no down-migrations, on purpose. An automatic rollback across a
# migration that has already run restores an image that cannot read its own schema, at speed,
# without asking. When the new version does not come up, the script says so, leaves it stopped
# where you can read the logs, and tells you the two ways out.
set -eu

COMPOSE_FILE=infra/docker-compose.deploy.yml
ENV_FILE=infra/deploy.env
LAST_DEPLOY=infra/.last-deploy
HEALTH_TIMEOUT=180

fail() {
    echo "redeploy: $1" >&2
    exit 1
}

[ -f "$COMPOSE_FILE" ] || fail "run this from the repository root; $COMPOSE_FILE not found"
[ -f "$ENV_FILE" ] || fail "$ENV_FILE not found. See docs/AWS_DEPLOYMENT.md"

NEW_TAG="${1:-}"
[ -n "$NEW_TAG" ] || fail "usage: sh infra/redeploy.sh <40-character commit sha>

Find the newest published build:
  gh api repos/<owner>/dip/actions/workflows/release.yml/runs \\
     --jq '.workflow_runs[0] | \"\\(.head_sha)  \\(.conclusion)  \\(.created_at)\"'"

# Forty hex characters, and nothing else.
#
# `latest` moves: a restart six weeks later silently upgrades to whatever main has become, and
# the version running is then a question nobody can answer from the server. A short SHA is not
# published at all — the release workflow tags images with the full one — so it would fail at the
# pull with a message about a manifest rather than about the mistake that was made.
case "$NEW_TAG" in
    *[!0-9a-f]* | "") fail "'$NEW_TAG' is not a commit sha. Forty lowercase hex characters, no tags, no 'latest'." ;;
esac
[ "${#NEW_TAG}" -eq 40 ] || fail "'$NEW_TAG' is ${#NEW_TAG} characters. A published tag is the full 40-character sha."

C="docker compose --env-file $ENV_FILE -f $COMPOSE_FILE"

OLD_TAG=$(grep '^IMAGE_TAG=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' ')
[ -n "$OLD_TAG" ] || fail "IMAGE_TAG is not set in $ENV_FILE"

if [ "$OLD_TAG" = "$NEW_TAG" ]; then
    echo "redeploy: already on $NEW_TAG. Nothing to do."
    exit 0
fi

echo "redeploy: $OLD_TAG"
echo "      ->  $NEW_TAG"
echo

# --- 1. back up ---------------------------------------------------------------------------
#
# Before anything is pulled, and a hard stop if it fails. Everywhere else in this stack a failed
# backup is logged and life goes on, which is right: an unbackupable database is serious but a
# restart loop is not how to report it. Here it is different. The next step runs migrations that
# cannot be undone, and the backup is the only way back from one that goes wrong.
echo "redeploy: taking a backup first"
if ! $C exec -T backup sh -c '. /etc/dip-backup.env && /usr/local/bin/backup.sh'; then
    fail "the backup failed, so nothing was deployed.

Fix the backup before deploying. Migrations run automatically and cannot be rolled back; the
archive is the only way out of one that goes wrong.

  $C logs --tail=50 backup"
fi
echo

# --- 2. pull ------------------------------------------------------------------------------
#
# Passed in the environment rather than written to the file, because a real environment variable
# beats --env-file in compose. The old version keeps serving throughout the download, and if the
# registry refuses — an expired pull token is the usual reason — deploy.env still names the build
# that is actually running.
echo "redeploy: pulling $NEW_TAG"
if ! IMAGE_TAG="$NEW_TAG" $C pull; then
    fail "could not pull $NEW_TAG, so nothing changed. Still running $OLD_TAG.

Either that build was never published, or the registry login has expired:
  echo \"\$GITHUB_TOKEN\" | docker login ghcr.io -u <username> --password-stdin"
fi
echo

# --- 3. swap ------------------------------------------------------------------------------
#
# The file is written only now, after the images are known to be on the host, so a machine that
# reboots mid-deploy comes back on something it can actually start. Rewritten through a temporary
# file with the mode set before the content: deploy.env holds every secret in the system, and a
# world-readable half-second is still world-readable.
TMP=$(mktemp "${ENV_FILE}.XXXXXX")
chmod 600 "$TMP"
sed "s|^IMAGE_TAG=.*|IMAGE_TAG=$NEW_TAG|" "$ENV_FILE" > "$TMP"
mv "$TMP" "$ENV_FILE"

printf '%s\n' "$OLD_TAG" > "$LAST_DEPLOY"
chmod 600 "$LAST_DEPLOY"

echo "redeploy: starting"
$C up -d
echo

# --- 4. wait ------------------------------------------------------------------------------
#
# Asked over the internal network through Caddy, which has a shell and a wget. That checks the
# container that is actually running rather than the public name, so it does not also depend on
# DNS, on the certificate, or on this host being able to resolve its own hostname — each of which
# fails differently and none of which is what this step is asking about. The public check comes
# after, and only warns.
echo "redeploy: waiting for the frontend (up to ${HEALTH_TIMEOUT}s)"
WAITED=0
while [ "$WAITED" -lt "$HEALTH_TIMEOUT" ]; do
    if $C exec -T caddy wget -q -O /dev/null --timeout=3 http://frontend:3000/api/health 2>/dev/null; then
        echo "redeploy: frontend is answering"
        break
    fi
    sleep 5
    WAITED=$((WAITED + 5))
done

if [ "$WAITED" -ge "$HEALTH_TIMEOUT" ]; then
    echo >&2
    echo "redeploy: FAILED — nothing answered within ${HEALTH_TIMEOUT}s. $NEW_TAG is deployed and not serving." >&2
    echo >&2
    echo "Read the logs. Both applications check their own configuration before opening a" >&2
    echo "connection and name what is wrong without printing its value:" >&2
    echo "  $C logs --tail=100 backend" >&2
    echo "  $C logs --tail=100 frontend" >&2
    echo >&2
    echo "If no migration ran, go back:" >&2
    echo "  sh infra/redeploy.sh $OLD_TAG" >&2
    echo >&2
    echo "If one did, going back will not work — the old image cannot read the new schema." >&2
    echo "Restore the archive taken at the start of this run. See docs/DEPLOYMENT.md." >&2
    exit 1
fi

# Anything still bouncing. The frontend answering does not mean the backend came up: it is a
# liveness probe on one process and says so in its own source.
RESTARTING=$($C ps --format '{{.Service}} {{.State}}' 2>/dev/null | grep -i restarting || true)
if [ -n "$RESTARTING" ]; then
    echo
    echo "redeploy: WARNING — something is restarting:"
    echo "$RESTARTING"
    echo "  $C logs --tail=100 backend"
fi

# --- 5. say where things stand -------------------------------------------------------------
echo
SITE_URL=$(grep '^SITE_URL=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' ')
if [ -n "$SITE_URL" ]; then
    if curl -fsS --max-time 10 "$SITE_URL/api/health" > /dev/null 2>&1; then
        echo "redeploy: $SITE_URL is up"
    else
        # A warning and not a failure. The stack is healthy on its own network; what has not been
        # proved is DNS, the certificate, or this host being able to reach its own public name,
        # and a host behind NAT often cannot do the last one even when everybody else can.
        echo "redeploy: note — could not reach $SITE_URL from this host."
        echo "          The stack is healthy internally. Check from a browser before assuming a fault;"
        echo "          if it is really down, the certificate is the usual cause: $C logs --tail=50 caddy"
    fi
fi

echo
echo "redeploy: done. Running $NEW_TAG."
echo "          Previous build was $OLD_TAG (also in $LAST_DEPLOY)."
echo "          To go back, if no migration ran:  sh infra/redeploy.sh $OLD_TAG"
