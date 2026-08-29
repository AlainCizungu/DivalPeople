#!/bin/sh
# Give DIP the ability to create accounts, as narrowly as Keycloak allows.
#
#   sh infra/keycloak/setup-identity-admin.sh          set it up
#   sh infra/keycloak/setup-identity-admin.sh rotate   replace the secret
#
# Run on the server, from the repository root, with the stack up and the realm already built by
# setup-realm.sh.
#
# THE SECRET IS NEVER PRINTED. It is written straight into infra/deploy.env and the script reports
# only that a value of some length landed there. The first version of this script printed it for
# you to copy, which took about a minute to go wrong: a credential on a terminal is a credential in
# a scrollback, in a screenshot, and in whatever you pasted the output into to ask what it meant.
# There is no reason a human ever has to read this string — the only thing that needs it is a
# container on the same host.
#
# WHAT THIS TURNS ON. Until it runs, /app/access shows the member list and no invite form, and
# accounts are made by hand with kcadm. After it runs, an institution's own TENANT_ADMIN can add
# and suspend their colleagues without the platform operator being in the loop. That is the whole
# point: four telecoms and fifteen banks means staff turnover at fifteen companies, and a platform
# operator who is a helpdesk for all of them is a platform operator who does nothing else.
#
# WHY IT IS A SEPARATE SCRIPT. Everywhere else, DIP only VERIFIES tokens somebody else issued.
# This is the first credential the backend holds that can CHANGE who exists. That is a real
# widening of what a compromised backend could do, so it is opt-in, separately, with its own
# command — rather than something a realm quietly acquires by being built.
#
# TWO ROLES, AND THE REASON NOT TO GRANT THE THIRD.
#
#   manage-users   create accounts, set roles, set passwords, disable
#   view-users     read an account to find out whose tenant it is
#
# NOT realm-admin, which is the tempting one because it is one line and always works. The
# difference is what a stolen secret buys. With these two, an attacker can create a user inside a
# tenant and give it roles — bad, and bounded: MembershipRules refuses PLATFORM_ADMIN, and the
# tenant comes from the caller's bound context, so the blast radius is one institution's book.
# With realm-admin, an attacker can rewrite the protocol mapper that puts tenant_id in the access
# token at all, which is the single thing every tenant boundary in this platform rests on. That is
# not a bigger version of the same problem; it is the end of multi-tenancy, and no amount of
# application code can detect it, because the tokens would be genuinely valid.
#
# The script checks afterwards that exactly these two are attached, and fails if a third appeared.
#
# IDEMPOTENT. Creating something that exists prints a conflict and carries on; the deploy.env lines
# are replaced rather than appended, so re-running does not leave two of anything. Re-running
# WITHOUT `rotate` reuses the existing secret; with it, the old one stops working immediately.
set -eu

MODE=${1:-setup}
case "$MODE" in
    setup|rotate) ;;
    *) echo "usage: $0 [setup|rotate]" >&2; exit 2 ;;
esac

ENV_FILE=infra/deploy.env
COMPOSE="docker compose --env-file $ENV_FILE -f infra/docker-compose.deploy.yml"
KC="/opt/keycloak/bin/kcadm.sh"
REALM=dip

# Deliberately not dip-web. One leak should not cost both sign-in and user management, and these
# two clients have different lifetimes: the web client's secret is known to the frontend on every
# request, this one is used a handful of times a week.
CLIENT=dip-identity-admin

fail() { echo "setup-identity-admin: $1" >&2; exit 1; }

[ -f "$ENV_FILE" ] || fail "run this from the repository root; $ENV_FILE not found"

value() { grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"'"'"' '; }

ADMIN=$(value KEYCLOAK_ADMIN)
ADMIN_PASSWORD=$(value KEYCLOAK_ADMIN_PASSWORD)

[ -n "$ADMIN" ] && [ -n "$ADMIN_PASSWORD" ] || fail "KEYCLOAK_ADMIN or KEYCLOAK_ADMIN_PASSWORD missing"

# --- the client, its service account, and exactly two roles -------------------
#
# Everything below runs inside the container. `sh -c` with the script on stdin keeps the admin
# password out of `docker compose exec`'s argument list, and so out of the process table.
if [ "$MODE" = setup ]; then
$COMPOSE exec -T keycloak sh -s <<INNER
set -eu

$KC config credentials --server http://localhost:8081 --realm master \\
    --user '$ADMIN' --password '$ADMIN_PASSWORD'

echo "--- client"
# Confidential, service account only. Every browser-facing flow is off: this client is never
# redirected to, never signs a person in, and has no business accepting a username and password.
# A client that can do those things is a client somebody can phish through.
$KC create clients -r $REALM \\
    -s clientId=$CLIENT \\
    -s enabled=true \\
    -s protocol=openid-connect \\
    -s publicClient=false \\
    -s serviceAccountsEnabled=true \\
    -s standardFlowEnabled=false \\
    -s implicitFlowEnabled=false \\
    -s directAccessGrantsEnabled=false \\
    -s 'redirectUris=[]' \\
    -s 'webOrigins=[]' \\
    -s description='Backend service account. Creates and suspends accounts on behalf of tenant administrators.' \\
    || echo "  (exists)"

CID=\$($KC get clients -r $REALM -q clientId=$CLIENT --fields id --format csv --noquotes)
[ -n "\$CID" ] || { echo "could not find the client after creating it" >&2; exit 1; }

echo "--- service account user"
SA=\$($KC get clients/\$CID/service-account-user -r $REALM --fields id --format csv --noquotes)
[ -n "\$SA" ] || {
    echo "the client has no service account user." >&2
    echo "serviceAccountsEnabled did not take; check the client in the admin console." >&2
    exit 1
}

echo "--- roles on realm-management"
# realm-management is Keycloak's own client, one per realm, holding the administrative roles. The
# roles are ITS client roles, not realm roles, which is why this is --cclientid and not the
# --rolename form used for DIP's own roles in setup-realm.sh. Getting that wrong produces "role
# not found" against a realm that plainly has the role.
$KC add-roles -r $REALM --uid \$SA --cclientid realm-management \\
    --rolename manage-users --rolename view-users || echo "  (already granted)"

echo "--- checking nothing else was granted"
# Read back, and be strict about it. This is the check that catches somebody adding realm-admin by
# hand in the console during an outage and forgetting, which is exactly how an over-privileged
# service account survives for a year. A grant that cannot be verified is not a grant.
GRANTED=\$($KC get-roles -r $REALM --uid \$SA --cclientid realm-management \\
    --fields name --format csv --noquotes | tr -d '\\r' | sort | tr '\\n' ' ')

echo "  granted: \$GRANTED"

for ROLE in \$GRANTED; do
    case "\$ROLE" in
        manage-users|view-users) ;;
        *)
            echo >&2
            echo "$CLIENT holds \$ROLE on realm-management, which it must not." >&2
            echo "Remove it before the secret goes anywhere:" >&2
            echo "  kcadm.sh remove-roles -r $REALM --uid \$SA --cclientid realm-management --rolename \$ROLE" >&2
            exit 1
            ;;
    esac
done

case "\$GRANTED" in
    *manage-users*) ;;
    *) echo "manage-users was not granted; the backend could not create anybody." >&2; exit 1 ;;
esac
case "\$GRANTED" in
    *view-users*) ;;
    *) echo "view-users was not granted; the backend could not check whose tenant an account is in, which is the check that keeps one institution out of another's staff." >&2; exit 1 ;;
esac
INNER
fi

# --- the secret, captured and never displayed --------------------------------
#
# A separate exec, whose entire stdout is the secret and nothing else. Splitting it from the noisy
# phase above is what makes the capture safe: one command, one line, no progress messages to strip
# and no chance of a "(exists)" ending up in deploy.env as a credential.
echo "--- secret"
if [ "$MODE" = rotate ]; then
    echo "  generating a new one; the previous secret stops working immediately"
    # Regenerate, then read back through the same path the setup case uses.
    #
    # Not one command, because `kcadm create` and `kcadm get` do not take the same options: create
    # has no --format or --noquotes, so asking it for a csv value fails with "Unknown options" —
    # AFTER authenticating and BEFORE regenerating anything, which is a rotation that reports
    # failure and silently leaves the exposed secret live. Regenerating and reading are two
    # different verbs and are now written as two.
    # Single-quoted so $KC and $CID survive this shell and are resolved by the one in the
    # container, where they are defined. Only $REALM is substituted here.
    ROTATE='$KC create clients/$CID/client-secret -r '"$REALM"' >&2'
else
    echo "  reading the existing one"
    ROTATE=:
fi

SECRET=$($COMPOSE exec -T keycloak sh -s <<INNER
set -eu
KC=$KC
\$KC config credentials --server http://localhost:8081 --realm master \\
    --user '$ADMIN' --password '$ADMIN_PASSWORD' > /dev/null
CID=\$(\$KC get clients -r $REALM -q clientId=$CLIENT --fields id --format csv --noquotes)
[ -n "\$CID" ] || { echo "no client called $CLIENT; run this without 'rotate' first" >&2; exit 1; }
# Anything this prints goes to stderr on purpose. Only the secret may reach stdout, because stdout
# is what gets captured and written into deploy.env.
$ROTATE
\$KC get clients/\$CID/client-secret -r $REALM --fields value --format csv --noquotes
INNER
)

# Carriage returns and stray whitespace removed here rather than in the pipeline above, because a
# `tr` on the end of that command substitution would swallow a non-zero exit from the exec — and
# the failure worth catching is exactly the one where the container errored and printed nothing.
SECRET=$(printf '%s' "$SECRET" | tr -d ' \t\r\n')

# A Keycloak secret is 32 characters. Anything much shorter means the capture picked up something
# that is not a secret — an error line, an empty response — and writing that into deploy.env would
# produce a backend that starts, reports itself configured, and fails at the moment somebody
# invites a colleague. Which is the failure this whole design exists to avoid.
[ "${#SECRET}" -ge 16 ] || fail \
    "the identity provider returned ${#SECRET} characters, which is not a secret. $ENV_FILE was not touched."

# --- deploy.env --------------------------------------------------------------
#
# Replaced, not appended. Docker Compose takes the LAST assignment in the file, so appending would
# work by accident and leave a trail of dead secrets above it — each one still valid until rotated,
# each one readable by anything that can read the file.
umask 077
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT INT TERM

# The marker comment is stripped along with the values. Stripping only the assignments left the
# comment behind, so a second run wrote a second copy of it — a cosmetic version of exactly the
# accumulation this replace exists to prevent, and the reason to dry-run a script that edits a
# file full of secrets before pointing it at the real one.
grep -v -e '^# setup-identity-admin:' -e '^DIP_IDENTITY_ADMIN_' "$ENV_FILE" > "$TMP" || true

# Command substitution drops trailing newlines, which is what stops the blank separator below
# accumulating one line per run.
KEPT=$(cat "$TMP")
printf '%s\n' "$KEPT" > "$TMP"

{
    echo ""
    # EVERY line carries the marker, because the strip above matches on it. A two-line comment
    # with the marker only on the first left its second line behind on every run — one orphan per
    # invocation, which is the same accumulation bug in a smaller costume.
    echo "# setup-identity-admin: written by the script. The secret belongs to the $CLIENT"
    echo "# setup-identity-admin: service account; nothing else uses it. Replace it with:"
    echo "# setup-identity-admin:   sh infra/keycloak/setup-identity-admin.sh rotate"
    echo "DIP_IDENTITY_ADMIN_BASE_URL=http://keycloak:8081"
    echo "DIP_IDENTITY_ADMIN_REALM=$REALM"
    echo "DIP_IDENTITY_ADMIN_CLIENT_ID=$CLIENT"
    echo "DIP_IDENTITY_ADMIN_CLIENT_SECRET=$SECRET"
} >> "$TMP"

# cat into the original rather than mv, so the file keeps its own ownership and permissions
# instead of inheriting mktemp's. deploy.env holds every secret on this host and its mode is not
# something to re-derive.
cat "$TMP" > "$ENV_FILE"

echo "  wrote ${#SECRET} characters to $ENV_FILE (not shown, and there is no reason to look)"

cat <<'NOTE'

--- restart the backend so it reads them

  docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d backend

`up -d` and not `restart`: restart reuses the existing container with the environment it was
created with, so it would come back knowing nothing about any of this.

Then the invite form appears on Access & permissions for anyone holding TENANT_ADMIN. If it does
not, the backend is missing at least one of base URL, client id and secret — the feature is all or
nothing on purpose, because a half-configured client fails at the moment somebody uses it rather
than at start-up.

--- if the secret is ever exposed

  sh infra/keycloak/setup-identity-admin.sh rotate

The old one stops working immediately and the new one never reaches a screen. Nothing but the
backend uses it, so the only cost is the restart above.
NOTE
