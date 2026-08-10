#!/usr/bin/env bash
#
# Local development helper.
#
#   ./infra/dev.sh up               start infrastructure and wait for it to be ready
#   ./infra/dev.sh down             stop it
#   ./infra/dev.sh token [user]     print an access token (default: operator-a)
#   ./infra/dev.sh check            end-to-end smoke test against the running API
#
# Credentials here are local-only fixtures from infra/keycloak/realm-dip.json.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

KEYCLOAK_URL="http://localhost:8081"
REALM="dip"
CLIENT_ID="dip-local"
# The client became confidential with ADR 0003, so the direct-grant calls below need the
# secret. Development only; it lives in the realm import next to this file.
CLIENT_SECRET="dip-local-development-secret"
API_URL="http://localhost:8080"
DEFAULT_PASSWORD="password"

info() { printf '\033[0;34m•\033[0m %s\n' "$1"; }
ok()   { printf '\033[0;32m✓\033[0m %s\n' "$1"; }
fail() { printf '\033[0;31m✗\033[0m %s\n' "$1" >&2; }

require_docker() {
    if ! docker info >/dev/null 2>&1; then
        fail "Docker is not running. Start Docker Desktop and try again."
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Whatever is holding the application's port, which this script does not start.
#
# Written after losing an afternoon to it twice over. First a container called dival_api, up for
# four days from an older build, held 8080; then an orphaned bootRun JVM from an earlier session
# held it. Both times `./gradlew bootRun` failed to bind and exited — one line that scrolls past in
# a hundred lines of Spring startup — while the squatter went on answering every request. So the
# API was reachable, the frontend worked, and the code actually running was several commits behind
# the checkout. Defects were investigated that did not exist, including a refusal message that had
# been deleted from the source.
#
# The first version of this reported only containers, reasoning that a local process on 8080 is
# almost always the backend the developer just started. That reasoning cost another round trip the
# next day, because an orphaned JVM looks exactly like a backend you just started.
#
# So everything is reported, and the discriminator is age rather than kind: a process that has been
# up for eleven seconds is the one you meant to start, and one up since yesterday is not.
#
# Kept out of check_ports() deliberately: that function returns early when our own compose stack is
# up, which is exactly the situation where a squatter hides.
# ---------------------------------------------------------------------------
warn_about_app_port() {
    local port="${API_HOST_PORT:-8080}"
    local pid
    pid="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1)" || return 0
    [ -n "$pid" ] || return 0

    # Asked of Docker rather than read from lsof, which reports the publishing proxy
    # ("com.docke") and never the container behind it.
    local container
    container="$(docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null \
        | awk -F'\t' -v needle=":$port->" 'index($2, needle) { print $1; exit }')"

    # Elapsed time, not start time: "up 2 days" answers the question, "started Tuesday" needs
    # arithmetic from whoever is reading it at the end of a long afternoon.
    local age
    age="$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ')"

    if [ -n "$container" ]; then
        fail "Port $port is held by the Docker container \"$container\" (up ${age:-unknown})."
        echo "    Stop it first:  docker stop $container" >&2
    else
        fail "Port $port is held by process $pid ($(ps -o comm= -p "$pid" 2>/dev/null | xargs), up ${age:-unknown})."
        echo "    If that is not the backend you just started, it is an orphan from an earlier" >&2
        echo "    session running older code.  Stop it:  kill $pid" >&2
    fi
    echo "    Until it is gone, ./gradlew bootRun fails to bind and exits, while that keeps" >&2
    echo "    answering — so the API you reach is whatever it is running, at whatever age." >&2
    echo >&2
}

# Fails fast with an actionable message instead of letting Compose die halfway through.
check_ports() {
    # If our own stack is already up, the ports are legitimately held by us — `up -d` is
    # idempotent from here, so there is nothing to warn about.
    if [ -n "$(docker compose -f "$COMPOSE_FILE" ps -q 2>/dev/null)" ]; then
        return 0
    fi

    local clashes=0
    local entries=(
        "${POSTGRES_HOST_PORT:-55432}|PostgreSQL|POSTGRES_HOST_PORT"
        "${REDIS_HOST_PORT:-56379}|Redis|REDIS_HOST_PORT"
        "${KEYCLOAK_HOST_PORT:-8081}|Keycloak|KEYCLOAK_HOST_PORT"
    )

    for entry in "${entries[@]}"; do
        local port name var
        IFS='|' read -r port name var <<< "$entry"

        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            fail "Port $port ($name) is already in use by another process."
            echo "    Free it, or choose another port:  export $var=<port>" >&2
            clashes=1
        fi
    done

    if [ "$clashes" -eq 1 ]; then
        exit 1
    fi
}

# Confirms the database reachable on the host port is OUR container and not some other
# PostgreSQL. Getting this wrong surfaces much later as 'role "dip" does not exist'.
verify_postgres() {
    local port="${POSTGRES_HOST_PORT:-55432}"

    info "Waiting for PostgreSQL..."
    for _ in $(seq 1 30); do
        if docker compose -f "$COMPOSE_FILE" exec -T postgres pg_isready -U dip -d dip >/dev/null 2>&1; then
            ok "PostgreSQL container is accepting connections"
            break
        fi
        sleep 2
    done

    # The container may be healthy while the host port reaches something else entirely.
    if command -v psql >/dev/null 2>&1; then
        if ! PGPASSWORD=dip psql -h 127.0.0.1 -p "$port" -U dip -d dip -tAc 'SELECT 1' >/dev/null 2>&1; then
            fail "127.0.0.1:$port is not our database."
            echo "    Another PostgreSQL is probably bound to that port." >&2
            echo "    Choose a free one:  export POSTGRES_HOST_PORT=<port>  && ./infra/dev.sh up" >&2
            exit 1
        fi
        ok "127.0.0.1:$port resolves to the DIP database"
    fi
}

wait_for() {
    local name="$1" url="$2" attempts="${3:-60}"
    info "Waiting for $name..."
    for _ in $(seq 1 "$attempts"); do
        if curl -fsS -o /dev/null "$url" 2>/dev/null; then
            ok "$name is ready"
            return 0
        fi
        sleep 2
    done
    fail "$name did not become ready at $url"
    return 1
}

fetch_token() {
    local user="${1:-operator-a}"
    curl -fsS -X POST \
        "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "client_id=$CLIENT_ID" \
        -d "client_secret=$CLIENT_SECRET" \
        -d "username=$user" \
        -d "password=$DEFAULT_PASSWORD" \
        -d "grant_type=password" \
        | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
}

case "${1:-up}" in
    up)
        require_docker
        check_ports
        warn_about_app_port
        info "Starting PostgreSQL, Redis and Keycloak..."
        docker compose -f "$COMPOSE_FILE" up -d
        verify_postgres
        wait_for "Keycloak realm '$REALM'" "$KEYCLOAK_URL/realms/$REALM"
        cat <<EOF

Infrastructure is up.

  PostgreSQL   127.0.0.1:${POSTGRES_HOST_PORT:-55432}   dip / dip
  Redis        127.0.0.1:${REDIS_HOST_PORT:-56379}
  Keycloak     $KEYCLOAK_URL   admin / admin

  Users        operator-a, operator-b, no-roles  (password: $DEFAULT_PASSWORD)

Next, in separate terminals:

  cd backend
  ./gradlew bootRun

  cd frontend
  cp .env.local.example .env.local     # first run only
  npm install                          # first run after ADR 0003
  npm run dev

  ./infra/dev.sh check

Sessions now live in Redis and tokens never reach the browser (ADR 0003), so the
frontend needs Redis running - which it already is, above.

EOF
        ;;

    down)
        docker compose -f "$COMPOSE_FILE" down
        ok "Stopped"
        ;;

    token)
        fetch_token "${2:-operator-a}"
        ;;

    check)
        warn_about_app_port
        info "Fetching a token for operator-a..."
        TOKEN="$(fetch_token operator-a)"
        if [ -z "$TOKEN" ]; then
            fail "Could not obtain a token. Is Keycloak up and the realm imported?"
            exit 1
        fi
        ok "Got a token ($(printf '%s' "$TOKEN" | wc -c | tr -d ' ') chars)"

        info "Checking the tenant_id claim is present..."
        PAYLOAD="$(printf '%s' "$TOKEN" | cut -d. -f2)"
        # base64url -> base64, padded
        PADDED="$(printf '%s' "$PAYLOAD" | tr '_-' '/+')"
        while [ $(( ${#PADDED} % 4 )) -ne 0 ]; do PADDED="${PADDED}="; done
        if printf '%s' "$PADDED" | base64 -d 2>/dev/null | grep -q 'tenant_id'; then
            ok "tenant_id claim present"
        else
            fail "tenant_id claim is MISSING — the protocol mapper did not apply."
            echo "  Recreate Keycloak to re-import the realm:" >&2
            echo "  docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak" >&2
            exit 1
        fi

        info "Calling the API..."
        if ! curl -fsS -o /dev/null "$API_URL/actuator/health"; then
            fail "The API is not responding at $API_URL. Start it with: cd backend && ./gradlew bootRun"
            exit 1
        fi
        ok "API is up"

        info "GET /api/v1/users/me as operator-a (provisions the local user record)..."
        ME="$(curl -fsS -H "Authorization: Bearer $TOKEN" "$API_URL/api/v1/users/me")"
        if printf '%s' "$ME" | grep -q '"tenantId"'; then
            ok "Local user provisioned: $ME"
        else
            fail "Unexpected response from /users/me: $ME"
            exit 1
        fi

        # A delta, not a total. This asserts that signing in again provisions nobody new — which
        # is the claim worth making. Counting members and expecting exactly one asserts something
        # else entirely: that operator-a is the only account that has ever signed in, which stops
        # being true the moment anybody demonstrates the product. That version failed on a working
        # database and said "provisioning is broken", which is the worst kind of check.
        info "Confirming the same identity does not create a second record..."
        members_now() {
            curl -fsS -H "Authorization: Bearer $TOKEN" "$API_URL/api/v1/users" \
                | grep -o '"id"' | wc -l | tr -d ' '
        }
        BEFORE="$(members_now)"
        curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" "$API_URL/api/v1/users/me"
        AFTER="$(members_now)"
        if [ "$AFTER" = "$BEFORE" ]; then
            ok "Provisioning is idempotent — still $AFTER member(s) after signing in again"
        else
            fail "Signing in again changed the member count: $BEFORE before, $AFTER after"
            exit 1
        fi

        info "GET /api/v1/tix/debt-records as operator-a..."
        RESPONSE="$(curl -fsS -H "Authorization: Bearer $TOKEN" "$API_URL/api/v1/tix/debt-records")"
        ok "Authorized request succeeded: ${RESPONSE:-[]}"

        # Reported as "Not Found" in red on the imports screen, which is what the browser shows
        # when the API's 404 body carries no message of its own. The status here separates the
        # three explanations that produce the same red box: 404 means the running process does not
        # have these routes and is older than the source tree; 403 means the account is missing
        # TIX_DECLARANT; 200 means the API is fine and the problem is in front of it.
        info "Probing the ingest routes the imports screen calls..."
        for ROUTE in /api/v1/ingest/sources /api/v1/ingest/batches; do
            STATUS="$(curl -s -o /dev/null -w '%{http_code}' \
                -H "Authorization: Bearer $TOKEN" "$API_URL$ROUTE")"
            if [ "$STATUS" = "200" ]; then
                ok "$ROUTE → 200"
            elif [ "$STATUS" = "404" ]; then
                fail "$ROUTE → 404. The API is running an older build than this checkout. Stop bootRun and start it again."
                exit 1
            else
                fail "$ROUTE → $STATUS"
                exit 1
            fi
        done

        info "Confirming an unauthenticated request is rejected..."
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' "$API_URL/api/v1/tix/debt-records")"
        if [ "$STATUS" = "401" ]; then
            ok "Unauthenticated request rejected with 401"
        else
            fail "Expected 401 for an unauthenticated request, got $STATUS"
            exit 1
        fi

        info "Confirming a user without TIX roles is refused..."
        NO_ROLE_TOKEN="$(fetch_token no-roles)"
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' \
            -H "Authorization: Bearer $NO_ROLE_TOKEN" "$API_URL/api/v1/tix/debt-records")"
        if [ "$STATUS" = "403" ]; then
            ok "User without TIX roles refused with 403"
        else
            fail "Expected 403 for a user without TIX roles, got $STATUS"
            exit 1
        fi

        info "Running a TIX inquiry that crosses the tenant boundary..."
        INQUIRY="$(curl -fsS -X POST "$API_URL/api/v1/tix/inquiries" \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -d '{"identifiers":[{"type":"NATIONAL_ID","value":"CD-1234-5678"}],
                 "fullName":"Jean Kabila","purpose":"ONBOARDING_CHECK"}')"

        # The debt is held by operator B while the caller is operator A, so a correct result
        # proves the exchange read worked *through* row-level security rather than around it.
        if printf '%s' "$INQUIRY" | grep -q '"OUTSTANDING_DEBT"'; then
            ok "Exchange read crossed operators: $INQUIRY"
        else
            fail "Expected OUTSTANDING_DEBT from the cross-operator inquiry, got: $INQUIRY"
            echo "    If this says NO_MATCH, the demo subject was not seeded — restart the API." >&2
            exit 1
        fi

        info "Confirming a tenant admin cannot provision tenants..."
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API_URL/api/v1/platform/tenants" \
            -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
            -d '{"name":"Should Not Exist","slug":"should-not-exist","edition":"TELECOM","defaultLocale":"fr"}')"
        if [ "$STATUS" = "403" ]; then
            ok "Tenant provisioning refused for a tenant admin (403)"
        else
            fail "Expected 403 when a tenant admin provisions a tenant, got $STATUS"
            exit 1
        fi

        info "Provisioning a tenant as platform-admin..."
        ADMIN_TOKEN="$(fetch_token platform-admin 2>/dev/null || true)"
        if [ -z "$ADMIN_TOKEN" ]; then
            fail "No platform-admin user in the realm."
            echo "    The realm gained one; recreate Keycloak to import it:" >&2
            echo "    docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak" >&2
            exit 1
        fi

        NEW_SLUG="check-$(date +%s)"
        CREATED="$(curl -fsS -X POST "$API_URL/api/v1/platform/tenants" \
            -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
            -d "{\"name\":\"Check Tenant\",\"slug\":\"$NEW_SLUG\",\"edition\":\"TELECOM\",\"defaultLocale\":\"fr\"}")"
        if printf '%s' "$CREATED" | grep -q "\"$NEW_SLUG\""; then
            ok "Tenant provisioned: $CREATED"
        else
            fail "Unexpected response provisioning a tenant: $CREATED"
            exit 1
        fi

        info "Confirming a duplicate slug is refused..."
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API_URL/api/v1/platform/tenants" \
            -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
            -d "{\"name\":\"Duplicate\",\"slug\":\"$NEW_SLUG\",\"edition\":\"TELECOM\",\"defaultLocale\":\"fr\"}")"
        if [ "$STATUS" = "409" ]; then
            ok "Duplicate slug refused with 409"
        else
            fail "Expected 409 for a duplicate slug, got $STATUS"
            exit 1
        fi

        info "Confirming operator-b sees only its own members..."
        TOKEN_B="$(fetch_token operator-b)"
        curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN_B" "$API_URL/api/v1/users/me"
        MEMBERS_B="$(curl -fsS -H "Authorization: Bearer $TOKEN_B" "$API_URL/api/v1/users")"
        if printf '%s' "$MEMBERS_B" | grep -q 'operator-a@example.test'; then
            fail "Cross-tenant leak: operator-b can see operator-a's members"
            exit 1
        fi
        ok "Member lists are tenant-scoped"

        printf '\n\033[0;32mEnd-to-end check passed.\033[0m\n\n'
        ;;

    *)
        fail "Unknown command: $1"
        sed -n '3,12p' "$0" >&2
        exit 1
        ;;
esac
