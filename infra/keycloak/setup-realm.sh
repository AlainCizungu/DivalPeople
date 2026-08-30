#!/bin/sh
# Build the dip realm from nothing, using Keycloak's own CLI inside the container.
#
#   sh infra/keycloak/setup-realm.sh
#
# Run on the server, from the repository root, with the stack up. Reads infra/deploy.env for the
# bootstrap admin password and the site hostname; asks for the one thing it cannot know, which is
# the password you want on your own account.
#
# WHY A SCRIPT AND NOT THE ADMIN CONSOLE. The console was unreachable for most of a day. It is
# served at whatever `hostname-admin` says, it authenticates against the master realm, and the
# proxy blocks `/realms/master*` on the public name on purpose — so the console loads from one
# origin and tries to log in at another, which the proxy refuses. Every fix moved the failure
# somewhere else.
#
# kcadm talks to the admin API over the container's own loopback. It does not care what hostname
# is configured, does not need a tunnel, and cannot be blocked by the proxy, because none of that
# is in the path. What was eight rounds of debugging is one command that either works or says why.
#
# The other gain is that a realm built by hand is a realm nobody can rebuild. This one is
# reproducible, reviewable, and diffable, which matters the first time somebody has to stand up a
# second environment or restore this one.
#
# IDEMPOTENT-ISH. Creating something that already exists makes kcadm print a conflict and carry
# on; the script does not stop. Re-running it is safe and mostly a no-op, but it will not repair
# a half-configured realm — for that, delete the realm and run it again.
set -eu

ENV_FILE=infra/deploy.env
COMPOSE="docker compose --env-file $ENV_FILE -f infra/docker-compose.deploy.yml"
KC="/opt/keycloak/bin/kcadm.sh"
REALM=dip
CLIENT=dip-web

# The first seeded operator. LocalTenantSeeder fixes these ids, so an account carrying this
# attribute opens a book that already has records in it.
TENANT_A=11111111-1111-1111-1111-111111111111

fail() { echo "setup-realm: $1" >&2; exit 1; }

[ -f "$ENV_FILE" ] || fail "run this from the repository root; $ENV_FILE not found"

# Trims surrounding whitespace and one layer of quotes, and nothing else.
#
# The first version deleted every space in the value, which is fine for a hostname and silently
# wrong for anything a person wrote: "Dival Intelligence Platform" arrived as one word, and a
# password containing a space would have been corrupted into something that simply fails to
# authenticate, with no indication that the file said otherwise.
value() {
    grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
              -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

ADMIN=$(value KEYCLOAK_ADMIN)
ADMIN_PASSWORD=$(value KEYCLOAK_ADMIN_PASSWORD)
SITE_URL=$(value SITE_URL)

[ -n "$ADMIN" ] && [ -n "$ADMIN_PASSWORD" ] || fail "KEYCLOAK_ADMIN or KEYCLOAK_ADMIN_PASSWORD missing"
[ -n "$SITE_URL" ] || fail "SITE_URL missing"

printf 'Username for your own account: '
read -r MY_USER
[ -n "$MY_USER" ] || fail "a username is required"

# Read without echo, and never as a command-line argument: kcadm takes it on stdin below, so it
# does not reach the process list or this shell's history.
printf 'Password for %s (not shown): ' "$MY_USER"
stty -echo 2>/dev/null || true
read -r MY_PASSWORD
stty echo 2>/dev/null || true
printf '\n'
[ -n "$MY_PASSWORD" ] || fail "a password is required"

# Everything below runs inside the container. `sh -c` with the script on stdin keeps the secrets
# out of `docker compose exec`'s argument list.
$COMPOSE exec -T keycloak sh -s <<INNER
set -eu

$KC config credentials --server http://localhost:8081 --realm master \\
    --user '$ADMIN' --password '$ADMIN_PASSWORD'

echo "--- realm"
$KC create realms -s realm=$REALM -s enabled=true || echo "  (exists)"

echo "--- client"
# BOTH SPELLINGS OF THE SITE URL, separated by Keycloak's '##'.
#
# post.logout.redirect.uris is matched exactly, and SITE_URL is written without a trailing slash
# while this line used to add one — so the application asked to return to
# https://dip.example.com and the client permitted only https://dip.example.com/ . Sign-in worked,
# every screen worked, and sign-out ended at "Invalid redirect uri" with nothing naming the
# character at fault. It was wrong from the first realm ever built and stayed hidden because
# nobody signs out while they are still getting something working.
#
# Registering both costs nothing: they are the same page, and an exact list of two is still an
# exact list. A wildcard would have been the quick fix and is the one place it must not be used —
# post-logout is a redirect the provider performs with a session in flight.
$KC create clients -r $REALM \\
    -s clientId=$CLIENT \\
    -s enabled=true \\
    -s protocol=openid-connect \\
    -s publicClient=false \\
    -s standardFlowEnabled=true \\
    -s directAccessGrantsEnabled=false \\
    -s serviceAccountsEnabled=false \\
    -s 'redirectUris=["$SITE_URL/api/auth/callback"]' \\
    -s 'webOrigins=["$SITE_URL"]' \\
    -s 'attributes={"post.logout.redirect.uris":"$SITE_URL##$SITE_URL/"}' || echo "  (exists)"

CID=\$($KC get clients -r $REALM -q clientId=$CLIENT --fields id --format csv --noquotes)
[ -n "\$CID" ] || { echo "could not find the client after creating it" >&2; exit 1; }

echo "--- tenant_id mapper"
# Into the access token above all. TenantResolutionFilter reads the claim from there; without it
# every sign-in succeeds and every screen is empty, which is the least legible failure available.
$KC create clients/\$CID/protocol-mappers/models -r $REALM \\
    -s name=tenant_id \\
    -s protocol=openid-connect \\
    -s protocolMapper=oidc-usermodel-attribute-mapper \\
    -s 'config."user.attribute"=tenant_id' \\
    -s 'config."claim.name"=tenant_id' \\
    -s 'config."jsonType.label"=String' \\
    -s 'config."access.token.claim"=true' \\
    -s 'config."id.token.claim"=true' \\
    -s 'config."userinfo.token.claim"=true' \\
    -s 'config.multivalued=false' || echo "  (exists)"

echo "--- realm roles"
# Every role in Roles.java. Creating them all, rather than only the ones this account needs,
# because a role that does not exist cannot be assigned later without somebody first working out
# what it should have been called.
for ROLE in PLATFORM_ADMIN TENANT_ADMIN HR_ADMIN HR_MANAGER PAYROLL_OFFICER FINANCE_OFFICER \\
            COMPLIANCE_OFFICER RECRUITER MANAGER EMPLOYEE AUDITOR TIX_INQUIRER TIX_DECLARANT; do
    $KC create roles -r $REALM -s name=\$ROLE > /dev/null 2>&1 && echo "  \$ROLE" \\
        || echo "  \$ROLE (exists)"
done

echo "--- user profile"
# Without this the next command silently does nothing useful.
#
# Keycloak 26 always runs the declarative user profile, and an attribute the profile does not
# declare is discarded on write without an error. tenant_id is not declared, so creating a user
# with attributes.tenant_id=[...] returns 201, reports success, and stores no attribute. The
# mapper below then reads a field that does not exist, the access token carries no claim, and
# TenantResolutionFilter refuses every request — so every sign-in works and every screen says
# "Authentication is required". Nothing in that chain points at the realm.
#
# ADMIN_EDIT rather than ENABLED: administrators may read and write unmanaged attributes, the
# account holder may not see or change them. tenant_id decides whose book this account opens and
# is not something the person it constrains should be able to edit.
$KC update users/profile -r $REALM -s 'unmanagedAttributePolicy=ADMIN_EDIT'

echo "--- user"
$KC create users -r $REALM \\
    -s username='$MY_USER' \\
    -s enabled=true \\
    -s emailVerified=true \\
    -s 'attributes.tenant_id=["$TENANT_A"]' || echo "  (exists)"

# exact=true: the username query is a substring match, so creating "alain" after
# "alaincizungu@gmail.com" exists would return both ids and every command after this would act on
# the wrong one, or on two.
UID_=\$($KC get users -r $REALM -q username='$MY_USER' -q exact=true --fields id --format csv --noquotes | head -1)
[ -n "\$UID_" ] || { echo "could not find the user after creating it" >&2; exit 1; }

$KC set-password -r $REALM --userid \$UID_ --new-password '$MY_PASSWORD'

$KC add-roles -r $REALM --uid \$UID_ \\
    --rolename TENANT_ADMIN --rolename TIX_INQUIRER --rolename TIX_DECLARANT

echo "--- checking the claim was actually stored"
# Read it back rather than trusting the create. The failure this catches is a write that reports
# success and stores nothing, which is how it was found: the realm looked correct in every listing
# and the application was unusable. A create that cannot be verified is not a create.
$KC get users/\$UID_ -r $REALM --fields attributes | grep -q '$TENANT_A' || {
    echo >&2
    echo "tenant_id was not stored on \$MY_USER." >&2
    echo "Every sign-in will succeed and every screen will say 'Authentication is required'." >&2
    echo "The user profile rejected the attribute; check unmanagedAttributePolicy above." >&2
    exit 1
}
echo "  tenant_id=$TENANT_A"

echo
echo "=== client secret, for OIDC_CLIENT_SECRET in deploy.env ==="
$KC get clients/\$CID/client-secret -r $REALM --fields value --format csv --noquotes
INNER
