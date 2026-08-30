#!/bin/sh
# Let people create their own account, and let DIP decide which institution they land in.
#
#   sh infra/keycloak/setup-registration.sh          check, and say what it would do
#   sh infra/keycloak/setup-registration.sh apply    do it
#
# Run on the server, from the repository root, with the stack up, the realm built, and a mail
# server configured (setup-email.sh).
#
# THE MAIL SERVER IS REQUIRED FOR USEFULNESS, NOT FOR SAFETY, and an earlier version of this
# comment had that wrong — it called registration without verification "an open door". It is not.
# JoiningService refuses to put anybody in an institution unless the token says the address is
# verified, so an account created without a mail server can register and then do precisely
# nothing. What it produces is a dead end: somebody signs up, waits for a message that cannot be
# sent, and concludes the product is broken. That is a good reason to refuse and a different one,
# and stating it accurately matters because the wrong reason invites somebody to "just turn off
# the check" one day.
#
# WHAT THIS OPENS, PRECISELY. A sign-up form appears on the sign-in page and anybody on the
# internet can create an account in this realm. That sounds alarming and is not, because of what
# such an account can do: nothing at all. It has no institution, so every screen refuses it, and
# DIP will only give it an institution if its address is VERIFIED and its domain has been mapped
# by the platform operator. Even then it arrives with no roles and sees a page saying its access
# is pending. Three separate things have to be true before a stranger reads a single record, and
# a human at their own institution decides the last one.
#
# WHY verifyEmail MATTERS MORE HERE THAN ANYWHERE. Without it, the address on an account is
# something typed into a form. With it, it is something the person demonstrably reads. The entire
# join decision — which institution's credit records this person will eventually see — rests on
# that difference and on nothing else. The application enforces it independently, by reading the
# email_verified claim, so this realm flag is the belt to that pair of braces rather than the only
# thing holding them up.
#
# THE TRAP THIS SCRIPT EXISTS TO AVOID. Realm-level verifyEmail applies to EVERY account, not just
# new ones. An account with NO email address is prompted to verify one at its next sign-in and has
# nothing to type into the form — it is locked out. The accounts setup-realm.sh creates have
# usernames and no addresses, so applying this blind locks out the operator running it along with
# the admin access needed to undo it. The check below refuses on exactly that.
#
# It does NOT refuse on an account whose address is merely unverified. An earlier version did, and
# that made the script impossible to run from the only state any realm is ever in beforehand:
# nothing verified, because verification was off and nothing was ever asked. Those accounts are
# prompted once, receive a link, and are fine. A guard that cannot be satisfied from the starting
# position is not a guard, it is a wall.
set -eu

MODE=${1:-check}
case "$MODE" in
    check|apply) ;;
    *) echo "usage: $0 [check|apply]" >&2; exit 2 ;;
esac

ENV_FILE=infra/deploy.env
COMPOSE="docker compose --env-file $ENV_FILE -f infra/docker-compose.deploy.yml"
KC="/opt/keycloak/bin/kcadm.sh"
REALM=dip

fail() { echo "setup-registration: $1" >&2; exit 1; }

[ -f "$ENV_FILE" ] || fail "run this from the repository root; $ENV_FILE not found"

value() {
    grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- \
        | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
              -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

ADMIN=$(value KEYCLOAK_ADMIN)
ADMIN_PASSWORD=$(value KEYCLOAK_ADMIN_PASSWORD)

[ -n "$ADMIN" ] && [ -n "$ADMIN_PASSWORD" ] || fail "KEYCLOAK_ADMIN or KEYCLOAK_ADMIN_PASSWORD missing"

$COMPOSE exec -T keycloak sh -s <<INNER
set -eu
MODE='$MODE'

$KC config credentials --server http://localhost:8081 --realm master \\
    --user '$ADMIN' --password '$ADMIN_PASSWORD' > /dev/null

echo "--- mail server"
# Checked rather than assumed. Registration with no way to send a verification message produces
# accounts that can never be verified and therefore never join anything — a sign-up form that
# silently leads nowhere, which is worse than no sign-up form.
# Without --fields: see the note in setup-email.sh. The projected form of this field comes back
# empty whatever the realm actually holds, so a check written against it refuses a correctly
# configured deployment.
$KC get realms/$REALM | tr ',' '\n' | grep -q '"host"' || {
    echo >&2
    echo "This realm has no mail server, so no address could ever be confirmed." >&2
    echo >&2
    echo "That is not a security hole — JoiningService refuses to put anybody in an institution" >&2
    echo "without a verified address, so accounts created here would register and then be able to" >&2
    echo "do nothing at all. It is a dead end, which is worse than a closed door: somebody signs" >&2
    echo "up, waits for a message that cannot be sent, and concludes the product is broken." >&2
    echo >&2
    echo "Run infra/keycloak/setup-email.sh first." >&2
    echo >&2
    echo "To open registration anyway, for a demonstration and not for real users:" >&2
    echo "  kcadm.sh update realms/$REALM -s registrationAllowed=true" >&2
    exit 1
}
echo "  present"

echo "--- accounts realm-wide email verification would affect"
# TWO DIFFERENT SITUATIONS, AND ONLY ONE OF THEM IS A PROBLEM.
#
# An account with NO email address cannot complete the verification prompt. It is shown a form it
# has nothing to type into, on every sign-in, and the person is locked out — including the operator
# running this, along with the admin access needed to undo it. That is the trap this check exists
# for and it still refuses.
#
# An account with an address that is merely UNVERIFIED is fine. It is prompted once, Keycloak sends
# the message, the person follows the link. That is the feature working, not a failure — and the
# first version of this check refused it too, which made the script unusable in exactly the state
# every realm is in before verification is turned on: nothing verified, because nothing was ever
# asked to be. A guard that cannot be satisfied from the starting position is not a guard, it is a
# wall.
#
# One account at a time, with grep rather than a JSON parser: the list endpoint returns a brief
# representation where an account with no address simply has no "email" key, which a flat search of
# the whole array cannot attribute to the right user. Deliberately no python3 — it is not in the
# Keycloak image, and a check that degrades to "found nothing" when its interpreter is missing
# would report all clear on the one question this script exists to ask.
NO_ADDRESS=""
UNVERIFIED=""
for U in \$($KC get users -r $REALM --fields username --format csv --noquotes | tr -d '\r'); do
    [ -n "\$U" ] || continue
    # exact=true, and it is not optional. Keycloak's username query is a SUBSTRING match, so
    # "alain" also returns "alaincizungu@gmail.com" — two ids in one variable, and the next call
    # gets two arguments and fails. Worse when it does not fail: any script that takes the first
    # line and acts on it is acting on whichever account the server happened to return first.
    ID=\$($KC get users -r $REALM -q username="\$U" -q exact=true --fields id --format csv --noquotes | tr -d '\r' | head -1)
    [ -n "\$ID" ] || continue

    INFO=\$($KC get users/\$ID -r $REALM --fields username,email,emailVerified,enabled)

    # Disabled accounts cannot be locked out of anything.
    echo "\$INFO" | grep -q '"enabled" : true' || continue

    if ! echo "\$INFO" | grep -q '"email" :'; then
        NO_ADDRESS="\$NO_ADDRESS \$U"
    elif ! echo "\$INFO" | grep -q '"emailVerified" : true'; then
        UNVERIFIED="\$UNVERIFIED \$U"
    fi
done

if [ -n "\$NO_ADDRESS" ]; then
    echo >&2
    echo "  These enabled accounts have no email address at all:" >&2
    echo "   \$NO_ADDRESS" >&2
    echo >&2
    echo "  Realm-wide verification would prompt each of them at their next sign-in, and an" >&2
    echo "  account with no address cannot complete that prompt. It would be locked out —" >&2
    echo "  including yours, including the admin access needed to undo this." >&2
    echo >&2
    echo "  Give each one an address, then run this again:" >&2
    echo >&2
    echo "    ID=\\\$($KC get users -r $REALM -q username=NAME -q exact=true --fields id --format csv --noquotes)" >&2
    echo "    $KC update users/\\\$ID -r $REALM -s email=someone@example.cd" >&2
    echo >&2
    exit 1
fi

if [ -n "\$UNVERIFIED" ]; then
    echo "  unverified, and that is fine — each will be asked once and emailed a link:"
    echo "   \$UNVERIFIED"
else
    echo "  none; every enabled account already has a verified address"
fi

if [ "\$MODE" != apply ]; then
    echo
    echo "--- nothing changed (this was a check)"
    echo "    Run again with 'apply' to open registration."
    exit 0
fi

echo "--- opening registration"
# registrationEmailAsUsername: the address IS the account. Two identifiers for one person is two
# things to get wrong, and the join decision is made from the address, so anything that lets the
# two drift apart is a way for them to disagree.
#
# verifyEmail: the line this whole script is careful about.
#
# resetPasswordAllowed was set by setup-email.sh and is repeated here because a self-registered
# user with no administrator to ask is exactly who needs it.
$KC update realms/$REALM \\
    -s registrationAllowed=true \\
    -s registrationEmailAsUsername=true \\
    -s verifyEmail=true \\
    -s loginWithEmailAllowed=true \\
    -s duplicateEmailsAllowed=false \\
    -s resetPasswordAllowed=true

echo "--- checking it took"
$KC get realms/$REALM --fields registrationAllowed,verifyEmail | grep -q 'true' || {
    echo "registration did not save." >&2
    exit 1
}
echo "  ok"
INNER

cat <<'NOTE'

--- what a stranger can now do

Create an account. Then nothing, until three separate things are true:

  1. They follow the link and verify their address. Until then it is a string in a form.
  2. Their address's domain has been mapped to an institution by you. Nobody else can map one,
     and no institution can claim its own — a competitor's domain would be just as easy to type.
  3. An administrator at that institution grants them a role. Joining gives membership and
     nothing else, because a work address proves employment and not authorisation.

--- map a domain to an institution

Platform administrator, on Participant organisations. One row per institution, added when it
signs. Free mail providers are refused: mapping gmail.com would put every Gmail user on earth
inside one institution's records, and it would look like it was working.

--- to close it again

  kcadm.sh update realms/dip -s registrationAllowed=false

Existing accounts are unaffected. Nobody new can sign up.
NOTE
