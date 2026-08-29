#!/bin/sh
# Give DIP the ability to create accounts, as narrowly as Keycloak allows.
#
#   sh infra/keycloak/setup-identity-admin.sh
#
# Run on the server, from the repository root, with the stack up and the realm already built by
# setup-realm.sh. Prints a client secret at the end; that secret goes in deploy.env and nowhere
# else.
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
# IDEMPOTENT-ISH, in the same way setup-realm.sh is: creating something that exists prints a
# conflict and carries on. Re-running is safe. It will NOT rotate the secret — for that, see the
# note at the bottom.
set -eu

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

# Everything below runs inside the container. `sh -c` with the script on stdin keeps the admin
# password out of `docker compose exec`'s argument list, and so out of the process table.
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
        "") ;;
        *)
            echo >&2
            echo "\$CLIENT holds \$ROLE on realm-management, which it must not." >&2
            echo "Remove it before putting the secret in deploy.env:" >&2
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

echo
echo "=== put these in infra/deploy.env ==="
echo "DIP_IDENTITY_ADMIN_BASE_URL=http://keycloak:8081"
echo "DIP_IDENTITY_ADMIN_REALM=$REALM"
echo "DIP_IDENTITY_ADMIN_CLIENT_ID=$CLIENT"
printf 'DIP_IDENTITY_ADMIN_CLIENT_SECRET='
$KC get clients/\$CID/client-secret -r $REALM --fields value --format csv --noquotes
INNER

cat <<'NOTE'

Then restart the backend so it reads them:

  docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d backend

The invite form appears on Access & permissions for anyone with TENANT_ADMIN. If it does not, the
backend has fewer than all three of base URL, client id and secret — the feature is all four or
nothing on purpose, because a half-configured client fails at the moment somebody uses it.

The base URL is the container name, not dip.dival.ai. Admin traffic has no business leaving the
private network to come back in through the proxy, and Keycloak's admin API on a public hostname
is an attack surface with no upside.

TO ROTATE THE SECRET, which you should do if it has ever been pasted anywhere it might be read:

  docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml exec -T keycloak \
    /opt/keycloak/bin/kcadm.sh create clients/<CID>/client-secret -r dip

then put the new value in deploy.env and restart the backend. Nothing else uses it, so the only
cost of rotating is the restart.
NOTE
