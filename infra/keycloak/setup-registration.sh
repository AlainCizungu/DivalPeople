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
# new ones. Turn it on with an account that has no email address, or an unverified one, and that
# person is prompted at their next sign-in — and an account with no address at all cannot complete
# the prompt. The accounts created by setup-realm.sh have usernames and no addresses, so this
# would lock out the operator who runs it, immediately, and the fix requires the admin access they
# have just lost. So the check below runs first and refuses to apply anything until every enabled
# account can survive it.
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

echo "--- accounts that realm-wide email verification would affect"
# The whole reason this script has a check mode, listed by username so the operator can act on
# them. One account at a time, with grep rather than a JSON parser. The list endpoint returns a brief
# representation and an account with no address simply has no "email" key at all, which cannot be
# told apart from the next account's in a flat text search of the whole array. Reading each user
# individually is a few more calls and gives an answer that is actually about that user.
#
# Deliberately no python3: it is not in the Keycloak image, and a check that silently degrades to
# "found nothing" when its interpreter is missing would report all clear on the one question this
# script exists to ask.
STRANDED=""
for U in \$($KC get users -r $REALM --fields username --format csv --noquotes | tr -d '\r'); do
    [ -n "\$U" ] || continue
    ID=\$($KC get users -r $REALM -q username="\$U" --fields id --format csv --noquotes | tr -d '\r')
    [ -n "\$ID" ] || continue

    INFO=\$($KC get users/\$ID -r $REALM --fields username,email,emailVerified,enabled)

    # Disabled accounts cannot be locked out of anything, so they are not this script's problem.
    echo "\$INFO" | grep -q '"enabled" : true' || continue

    # Covers both cases at once: no address means no emailVerified true either.
    echo "\$INFO" | grep -q '"emailVerified" : true' || STRANDED="\$STRANDED \$U"
done

if [ -n "\$STRANDED" ]; then
    echo >&2
    echo "  These accounts have no address, or an unverified one:" >&2
    echo "   \$STRANDED" >&2
    echo >&2
    echo "  Turning on realm-wide email verification would prompt each of them at their next" >&2
    echo "  sign-in, and an account with NO address cannot complete that prompt — it would be" >&2
    echo "  locked out, including yours, including the admin access needed to undo this." >&2
    echo >&2
    echo "  Give each one an address and mark it verified, then run this again:" >&2
    echo >&2
    echo "    ID=\\\$($KC get users -r $REALM -q username=NAME --fields id --format csv --noquotes)" >&2
    echo "    $KC update users/\\\$ID -r $REALM -s email=someone@example.cd -s emailVerified=true" >&2
    echo >&2
    exit 1
fi
echo "  none; every enabled account has a verified address"

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
