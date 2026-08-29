#!/bin/sh
# Give the realm a mail server, so DIP can send invitations and password resets.
#
#   sh infra/keycloak/setup-email.sh
#
# Run on the server, from the repository root, with the stack up and the realm already built.
# Reads the SMTP settings from infra/deploy.env. Prints nothing secret.
#
# WHAT THIS CHANGES. Three things, and only the first is the one you came for:
#
#   1. An invited colleague gets a link and chooses their own password. Today an administrator
#      reads a generated password off a screen and passes it on by hand, which means the password
#      exists in a chat window, a screenshot, or a piece of paper. A link that expires is not a
#      password and cannot be reused.
#   2. Forgotten passwords stop being a support ticket. Without a mail server there is no reset
#      path at all — every forgotten password today is somebody with server access running kcadm.
#   3. Addresses get verified. An invitation delivered to a mistyped address currently succeeds
#      silently; with VERIFY_EMAIL the account cannot be used until somebody proves they read the
#      mail sent to it.
#
# WHY SES AND NOT A MAILBOX. Any SMTP server works and the script does not care which. Amazon SES
# in the same region is the path of least resistance for this deployment: no extra host to keep
# running, and the credentials are scoped to sending only. What matters far more than the provider
# is that the FROM domain is one you control and have published SPF and DKIM for — mail from a
# credit registry that lands in spam is an invitation nobody acts on, and mail from a domain
# anybody can forge is a phishing kit aimed at your own participants.
#
# SES STARTS IN A SANDBOX. Until you ask for production access it will only deliver to addresses
# you have verified individually, which is fine for testing and useless for real invitations. Ask
# early; approval is not instant.
set -eu

ENV_FILE=infra/deploy.env
COMPOSE="docker compose --env-file $ENV_FILE -f infra/docker-compose.deploy.yml"
KC="/opt/keycloak/bin/kcadm.sh"
REALM=dip

fail() { echo "setup-email: $1" >&2; exit 1; }

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

SMTP_HOST=$(value SMTP_HOST)
SMTP_PORT=$(value SMTP_PORT)
SMTP_USER=$(value SMTP_USER)
SMTP_PASSWORD=$(value SMTP_PASSWORD)
SMTP_FROM=$(value SMTP_FROM)
SMTP_FROM_NAME=$(value SMTP_FROM_DISPLAY_NAME)
SMTP_REPLY_TO=$(value SMTP_REPLY_TO)

[ -n "$ADMIN" ] && [ -n "$ADMIN_PASSWORD" ] || fail "KEYCLOAK_ADMIN or KEYCLOAK_ADMIN_PASSWORD missing"
[ -n "$SITE_URL" ] || fail "SITE_URL missing"

# All or nothing, in the same shape as the identity-admin settings. A half-configured mail server
# fails when somebody is invited, which is the worst moment to find out — and the fallback is
# harmless, because the invitation simply goes back to showing a password.
[ -n "$SMTP_HOST" ] || fail "SMTP_HOST is empty. Fill in the SMTP block in $ENV_FILE first."
[ -n "$SMTP_FROM" ] || fail "SMTP_FROM is empty. It must be an address at a domain you control."
[ -n "$SMTP_USER" ] && [ -n "$SMTP_PASSWORD" ] || fail "SMTP_USER or SMTP_PASSWORD is empty."

SMTP_PORT=${SMTP_PORT:-587}
SMTP_FROM_NAME=${SMTP_FROM_NAME:-Dival Intelligence Platform}
SMTP_REPLY_TO=${SMTP_REPLY_TO:-$SMTP_FROM}

case "$SMTP_FROM" in
    *@gmail.com|*@yahoo.com|*@hotmail.com|*@outlook.com)
        fail "SMTP_FROM is a free mailbox. Invitations to a credit registry must come from a domain you control and can publish SPF and DKIM for, or they will be filtered — and a domain anybody can send as is a phishing kit pointed at your participants." ;;
esac

echo "--- mail server"
echo "  host $SMTP_HOST:$SMTP_PORT, from $SMTP_FROM"

# STARTTLS on 587, implicit TLS on 465. Getting this pair wrong produces a connection that hangs
# rather than an error, so it is derived from the port rather than asked for.
if [ "$SMTP_PORT" = "465" ]; then
    SSL=true; STARTTLS=false
else
    SSL=false; STARTTLS=true
fi

# FED AS A DOCUMENT ON STDIN, because -s cannot set this field.
#
# Two attempts failed before this one, both silently, both reporting success:
#
#   -s 'smtpServer.host=...'    dotted paths, one field at a time
#   -s 'smtpServer={"host":..}' the whole map as a JSON value
#
# smtpServer is a Map<String,String>, and kcadm's -s does not put a JSON object into one. It
# accepted both forms, returned zero, and stored nothing — while `-s resetPasswordAllowed=true` on
# the very same command worked, which is what made this so slow to see: realm updates plainly
# functioned, so the fault looked like it had to be somewhere else.
#
# `-f -` sends a representation rather than a field assignment, and kcadm still merges it with what
# is already there. Proven by a curl PUT to the same endpoint returning 204 and the value appearing.
#
# Every value is a STRING, including port and the booleans, because Map<String,String> is what it
# deserialises into.
SMTP_JSON=$(printf \
    '{"smtpServer":{"host":"%s","port":"%s","from":"%s","fromDisplayName":"%s","replyTo":"%s","auth":"true","user":"%s","password":"%s","ssl":"%s","starttls":"%s"}}' \
    "$SMTP_HOST" "$SMTP_PORT" "$SMTP_FROM" "$SMTP_FROM_NAME" "$SMTP_REPLY_TO" \
    "$SMTP_USER" "$SMTP_PASSWORD" "$SSL" "$STARTTLS")

$COMPOSE exec -T keycloak sh -s <<INNER
set -eu

$KC config credentials --server http://localhost:8081 --realm master \\
    --user '$ADMIN' --password '$ADMIN_PASSWORD' > /dev/null

# A quoted heredoc, so nothing in the JSON — a password with a dollar sign, say — is expanded a
# second time by the shell inside the container.
$KC update realms/$REALM -f - <<'SMTP_DOCUMENT'
$SMTP_JSON
SMTP_DOCUMENT

echo "--- sign-in settings that need a mail server to work"
# resetPasswordAllowed puts "Forgot password?" on the sign-in page. It is useless without SMTP,
# which is why it is set here rather than in setup-realm.sh: a link that silently fails to send
# is worse than no link.
#
# verifyEmail stays FALSE at the realm level deliberately. Turning it on demands verification from
# every existing account at next sign-in, including the ones created before this ran, and locks
# out anybody whose address was mistyped months ago. New accounts get VERIFY_EMAIL as a one-off
# action on their invitation instead, which achieves the same thing without a flag day.
$KC update realms/$REALM \\
    -s resetPasswordAllowed=true \\
    -s loginWithEmailAllowed=true \\
    -s duplicateEmailsAllowed=false \\
    -s verifyEmail=false

echo "--- the invitation link has to be allowed to come back here"
# execute-actions-email builds a link that returns the person to the application when they are
# done. Keycloak validates that destination against the client's redirect URIs, so the site root
# has to be listed or the invitation is refused with an invalid_redirect_uri that names nothing.
#
# Exactly the root, not a wildcard. A wildcard here would widen what the sign-in flow itself will
# redirect to, which is the one place an open redirect turns into a stolen session.
CID=\$($KC get clients -r $REALM -q clientId=dip-web --fields id --format csv --noquotes)
[ -n "\$CID" ] || { echo "no dip-web client; run setup-realm.sh first" >&2; exit 1; }

$KC update clients/\$CID -r $REALM \\
    -s 'redirectUris=["$SITE_URL/api/auth/callback","$SITE_URL/"]'

echo "--- checking it was stored"
# READ BACK WITHOUT --fields, because --fields is what was lying.
#
# Asking kcadm for the realm with --fields smtpServer returns an empty object even when the mail
# server is fully configured: the projection drops the contents of a nested map. A curl GET against
# the same endpoint shows every value.
#
# That check produced three rounds of fixes for a configuration that may have been correct from the
# first attempt. A verification step that can report failure for a healthy system is worse than no
# verification: it does not merely fail to catch a bug, it manufactures one, and everything done in
# response is work on the wrong problem.
#
# NO BACKTICKS ANYWHERE BELOW, and none in any comment inside this heredoc. A here-document whose
# delimiter is unquoted performs command substitution on its whole body, comments included — there
# are no comments in a heredoc, only text. An earlier version of this note quoted a kcadm command
# in backticks and dash tried to RUN it, failing on a brace inside. Under a shell less strict it
# would have run silently instead.
# No escaped double quotes in here either. An unquoted heredoc treats backslash differently in
# bash and in dash, and this script runs under sh, which on the deployment host is dash — so a
# pattern that parses on a laptop can be a syntax error on the server. Matching the hostname alone
# is enough and needs no quoting at all.
$KC get realms/$REALM | grep -q "$SMTP_HOST" || {
    echo >&2
    echo "The mail server did not save, and Keycloak reported no error." >&2
    echo "This is what the realm holds for SMTP (password masked):" >&2
    $KC get realms/$REALM | grep -i smtp | sed 's/password.*/password: <set>/' >&2
    exit 1
}
echo "  ok"
INNER

cat <<NOTE

--- then turn invitations on for the backend

Add to $ENV_FILE:

  DIP_IDENTITY_ADMIN_INVITE_BY_EMAIL=true
  DIP_IDENTITY_ADMIN_INVITE_REDIRECT_URI=$SITE_URL/

and recreate the backend:

  $COMPOSE up -d backend

Until that variable is true, DIP keeps showing a password on screen. The realm can send mail and
the application has not been told to use it — deliberately two steps, so you can prove sending
works before an invitation depends on it.

--- prove it before you rely on it

Invite yourself at an address you can read. In the SES sandbox that address must be verified in
SES first, or the send fails and the invitation rolls back.

If nothing arrives, look at the backend log for the status, then at SES's own bounce and
suppression lists. Keycloak reports a failure to hand the mail over; it cannot tell you what
happened after that.
NOTE
