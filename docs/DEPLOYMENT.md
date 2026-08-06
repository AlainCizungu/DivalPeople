# Deployment

How to run DIP somewhere other than a laptop, and what to do when it goes wrong.

This describes a single-host deployment with Docker Compose behind Caddy. It is deliberately the
simplest thing that is defensible: one server, TLS, private networking, separate database roles,
and images built by CI rather than on the box. It will carry a pilot and a demo comfortably. It
will not survive the loss of the host, and that limitation is stated at the end rather than
buried.

---

## What is deployed

| Piece | Reachable from outside | Notes |
|---|---|---|
| Caddy | **Yes**, 80 and 443 | The only published port. Terminates TLS, renews certificates. |
| Frontend (Next.js) | No | Holds the session. The only thing that sees a bearer token. |
| Backend (Spring Boot) | No | Never reachable from a browser. |
| Keycloak | Through Caddy, on its own hostname | Admin console is blocked at the proxy. |
| PostgreSQL | No | Two databases, four roles. |
| Redis | No | Sessions. Password-protected. |

Nothing but Caddy publishes a port. A database reachable from the internet is found by a scanner
within hours, and a strong password is not a substitute for not being there.

---

## Before the first deploy

1. **A host.** Two vCPUs and 4 GB of memory is enough to start; Keycloak and the JVM are the
   memory. Docker Engine and the Compose plugin installed.

2. **Two DNS records**, both pointing at the host, both resolving *before* you start:

   ```
   dip.example.com  A  <host ip>
   id.example.com   A  <host ip>
   ```

   Caddy requests certificates on first start. If the names do not resolve yet it will fail,
   back off, and retry — and Let's Encrypt rate-limits failures, so get this right first.

3. **A firewall.** Allow 80, 443 and your SSH port. Nothing else. On a fresh Ubuntu host:

   ```
   ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
   ```

4. **A registry pull secret**, if the packages are private:

   ```
   echo "$GITHUB_TOKEN" | docker login ghcr.io -u <username> --password-stdin
   ```

---

## First deploy

```bash
git clone https://github.com/<org>/dip.git && cd dip

cp infra/deploy.env.example infra/deploy.env
chmod 600 infra/deploy.env
```

Fill in `infra/deploy.env`. Generate **every** secret separately:

```bash
openssl rand -base64 32
```

Reusing one secret in two places means one leak is two compromises, and rotating it is two
outages. Set `IMAGE_TAG` to a full commit SHA from a green build — never `latest`, for the
reason given in the file.

Then:

```bash
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml logs -f backend
```

The backend runs Flyway at start-up and then checks its own configuration. If it refuses to
start, read the message: it names the variables that are wrong and never prints their values.

### Then create the realm

The stack starts with Keycloak empty. The development realm in `infra/keycloak/` is a **fixture**
— it contains a known client secret and four users with published passwords. It must never be
imported into a deployment.

1. Reach the admin console over an SSH tunnel, because the proxy blocks it:

   ```bash
   ssh -L 8081:localhost:8081 user@host
   docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
     exec keycloak /opt/keycloak/bin/kc.sh --version   # sanity check
   ```

   Then open `http://localhost:8081/admin` through the tunnel.

2. Create a realm named `dip`.

3. Create a **confidential** client (`Client authentication` on) with:
   - Client ID matching `OIDC_CLIENT_ID`
   - Valid redirect URI: `https://dip.example.com/api/auth/callback`
   - Valid post-logout redirect URI: `https://dip.example.com/`
   - Web origin: `https://dip.example.com`

   Copy the generated secret into `OIDC_CLIENT_SECRET` and restart the frontend.

4. Create the realm roles the platform uses. They are listed in
   `backend/src/main/java/ai/dival/dip/common/security/Roles.java`.

5. Every user needs a `tenant_id` attribute, mapped into the token. Without it the request has no
   tenant and the application refuses it — which is correct, and is the first thing to check when
   a new user cannot see anything.

6. Disable the bootstrap admin account once a named administrator exists.

---

## Deploying a new version

```bash
# Edit IMAGE_TAG to the new commit SHA, then:
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml pull
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d
```

**Take a backup first** (below). Migrations run automatically and there is no automatic rollback:
Flyway has no `down` migration in this project, deliberately, because a generated rollback that
has never been tested is worse than not having one.

To go back to a previous version, set `IMAGE_TAG` to the older SHA — but only if no migration ran
in between. If one did, restore the backup instead. The schema is what makes a rollback
irreversible, not the image.

---

## Backups

Nothing here is backed up automatically. That is the largest gap in this document and you should
close it before there is anything in the database you would mind losing.

```bash
# Both databases, compressed, timestamped.
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
  exec -T postgres pg_dumpall -U "$POSTGRES_OWNER_USER" \
  | gzip > "dip-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
```

Put that on a schedule, and **send the file off the host**. A backup on the same disk as the
database protects you against exactly one failure mode, and not the common one.

Restore, on an empty stack:

```bash
gunzip -c dip-20260806T090000Z.sql.gz \
  | docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
    exec -T postgres psql -U "$POSTGRES_OWNER_USER" -d postgres
```

**A backup you have never restored is not a backup.** Restore one into a scratch stack before you
need to do it under pressure.

---

## Rotating a secret

### The application database password

The migration that sets it (`V4`) has already run and Flyway will not re-run it, so changing
`POSTGRES_APP_PASSWORD` alone does nothing. Change it in both places:

```bash
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
  exec postgres psql -U "$POSTGRES_OWNER_USER" -d "$POSTGRES_DB" \
  -c "ALTER ROLE dip_app WITH PASSWORD 'the-new-secret';"

# then update POSTGRES_APP_PASSWORD in infra/deploy.env and:
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d backend
```

### The OIDC client secret

Regenerate it in Keycloak, update `OIDC_CLIENT_SECRET`, restart the frontend. Existing sessions
survive — they are Redis-backed and do not hold the client secret.

### The Redis password

Update `REDIS_PASSWORD` and restart Redis and both applications. **This signs everybody out**,
because the sessions are in Redis and the new password loses the connection to them. Do it
deliberately, not during the working day.

### If a secret has actually leaked

Rotate all of them, then invalidate every session:

```bash
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
  exec redis redis-cli -a "$REDIS_PASSWORD" FLUSHALL
```

Then read `audit_event` for the period in question. That table exists for this.

---

## Verifying an image before you trust it

Both images can be proved to refuse a bad configuration, without a database and without
deploying anything. Do this after any change to the Dockerfiles or to the configuration checks.

```bash
# Deliberately wrong in five ways at once: one database account for both roles, passwords from
# this repository, a plain-HTTP issuer, and a database on localhost.
docker run --rm \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DIP_DB_URL=jdbc:postgresql://localhost:5432/dip \
  -e DIP_DB_USER=dip -e DIP_DB_PASSWORD=dip \
  -e DIP_APP_DB_USER=dip -e DIP_APP_DB_PASSWORD=dip \
  -e REDIS_HOST=redis -e REDIS_PORT=6379 \
  -e DIP_OIDC_ISSUER_URI=http://localhost:8081/realms/dip \
  dip-backend:test
```

It must exit non-zero. What it must **not** do is start.

```bash
# Same idea: no client secret, plain HTTP, localhost.
docker run --rm \
  -e NODE_ENV=production \
  -e SITE_URL=http://localhost:3000 \
  -e OIDC_ISSUER=http://localhost:8081/realms/dip \
  -e OIDC_CLIENT_ID=dip-web \
  -e API_BASE_URL=http://backend:8080 \
  -e REDIS_URL=redis://redis:6379 \
  dip-frontend:test
```

It must exit before it listens, not on the first request. That is what `src/instrumentation.ts`
is for: a container that exits immediately is caught by whoever deployed it, while they are still
watching. One that starts healthy and fails on the first real request is caught by a user, and
looks like an outage rather than a typo.

---

## When something is wrong

**Start here, in this order.** Most of what goes wrong is configuration, and the errors say so.

```bash
C="docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml"

$C ps                      # what is actually running, and what keeps restarting
$C logs --tail=200 backend
$C logs --tail=200 frontend
$C logs --tail=100 caddy   # certificate problems live here
```

| Symptom | Look at |
|---|---|
| Backend exits at start-up with "Refusing to start" | It is telling you which variables are wrong. Fix those. |
| Backend exits with "Could not resolve placeholder" | A variable is missing from `deploy.env` entirely. |
| Frontend exits with "Refusing to start with a development configuration" | Same idea: https, localhost, or a missing client secret. |
| Site does not load, no certificate | DNS is not pointing here yet, or 80 is blocked. Caddy's log says which. |
| Login loops back to the login page | Redirect URI in Keycloak does not exactly match `SITE_URL` + `/api/auth/callback`. |
| Logged in, but every page is empty | The user has no `tenant_id` attribute, or it is not mapped into the token. |
| Logged in, but "not linked to an employee record" | Expected. Link the sign-in to an employee in the people directory. |
| 502 from the proxy | The frontend container is down or still starting. `$C ps`. |
| Slow, then unresponsive | `docker stats`. The JVM is capped at 75% of the container limit; if the host is swapping, it is memory. |

---

## What this deployment does not do

Stated plainly, because a deployment guide that implies more resilience than it delivers is
worse than none.

- **One host, no redundancy.** Lose the server and the service is down until you rebuild it.
  Restoring from a backup onto a new host is the recovery plan, and it is manual.
- **No automatic backups.** See above. This is the first thing to fix.
- **Deployment is manual.** CI publishes images; a person pulls them. There is no continuous
  deployment, and no automated rollback.
- **No log aggregation and no alerting.** If something breaks at night, you find out in the
  morning. `docker logs` on the host is the whole story.
- **Migrations have no tested rollback path.** Backup first, every time.
- **Keycloak is configured by hand.** The realm is not in version control for a deployment, so
  rebuilding it is a manual exercise. Export the realm after configuring it and keep that export
  somewhere safe — without a client secret in it.
- **The application-role password rotation is manual**, for the Flyway reason above.

None of these stop a pilot. All of them matter before a telecom depends on it.
