# Deployment

How to run DIP somewhere other than a laptop, and what to do when it goes wrong.

Provider-neutral. For the AWS instance at `dip.dival.ai` — sizing, security group, Route 53,
getting archives into S3 — read this first and then [AWS_DEPLOYMENT.md](AWS_DEPLOYMENT.md).

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

The backend checks its own configuration **before** it opens any connection, then runs Flyway.
If it refuses to start, read the message: it lists every fault at once, names the variables that
are wrong, and never prints their values.

### Then create the realm

The stack starts with Keycloak empty. The development realm in `infra/keycloak/` is a **fixture**
— it contains a known client secret and four users with published passwords. It must never be
imported into a deployment.

1. Reach the admin console over an SSH tunnel, because the proxy blocks it.

   Keycloak publishes port 8081 on the host's loopback address only — `127.0.0.1:8081:8081` in
   the compose file. Nothing on the network can reach it; the firewall and the security group
   both refuse that port. An SSH tunnel is the only way in:

   ```bash
   ssh -i ~/.ssh/dip-test.pem -L 8081:localhost:8081 ubuntu@dip.dival.ai
   ```

   For the deployed instance, that key and hostname are the real ones. Neither is a secret — the
   hostname is public and the key file lives on the operator's own machine — and writing them down
   is the point: this document previously said `<your-key.pem>` and `<host>`, and the day the
   terminal closed it took four rounds of searching a laptop to get back in. A runbook that only
   works for somebody who already remembers the answer is not a runbook.

   Better still, in `~/.ssh/config` on the operator's machine, so nothing has to be remembered:

   ```
   Host dip
       HostName dip.dival.ai
       User ubuntu
       IdentityFile ~/.ssh/dip-test.pem
   ```

   Then `ssh dip`, and `ssh -L 8081:localhost:8081 dip` for the tunnel.

   **Keep the key out of `~/Downloads`.** That is the directory people empty when a disk fills up,
   and this key is the only way into a host holding other institutions' credit records. Losing it
   means the EC2 console's Instance Connect is the way back — which works, and is worth knowing
   before it is needed. The `age` backup key has no equivalent fallback.

   An earlier version of this document had you forward to the container's address on the Docker
   bridge, on the argument that publishing a port at all was the greater risk. That address
   changes every time the container is recreated — several times an hour during a first
   deployment — and each stale copy fails as a connection refused that reads as Keycloak being
   down. The procedure was unusable, which is worse than the edit it was guarding against.

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
sh infra/redeploy.sh <40-character commit sha>
```

It backs up, pulls while the old version keeps serving, swaps, waits for the frontend to answer,
and prints the line that puts it back. It refuses `latest` and short SHAs, and it **stops without
deploying anything if the backup fails** — which is the one place in this stack where a failed
backup is fatal rather than logged, because the next step runs migrations that cannot be undone.

By hand, if you would rather see each step:

```bash
# Edit IMAGE_TAG to the new commit SHA, then:
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml pull
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml up -d
```

**Take a backup first** (below). Migrations run automatically and there is no automatic rollback:
Flyway has no `down` migration in this project, deliberately, because a generated rollback that
has never been tested is worse than not having one. The script does not roll back on failure for
the same reason — an automatic rollback across a migration that has already run restores an image
that cannot read its own schema, at speed, without asking.

To go back to a previous version, set `IMAGE_TAG` to the older SHA — but only if no migration ran
in between. If one did, restore the backup instead. The schema is what makes a rollback
irreversible, not the image.

---

## Backups

The stack takes one automatically, encrypted, on the schedule you set in `BACKUP_CRON`. Two
things about it are worth understanding before you rely on it.

**The archive is encrypted to a public key, and the private key must not be on the server.**
Generate the pair somewhere else:

```bash
age-keygen -o dip-backup.key      # keep this file; it is the only way to read a backup
```

Put the `age1…` public line in `BACKUP_AGE_PUBLIC_KEY`. Keep `dip-backup.key` in a password
manager or anywhere that is not the machine being backed up. A backup is a complete copy of every
salary, national identifier and debt record in the system; if the key that opens it sits next to
it, encrypting it protected you from nothing.

**Losing the private key means losing every backup.** There is no recovery path. Store a second
copy somewhere independent.

**Nothing copies the archives off the host.** `BACKUP_HOST_DIR` is a bind mount so you can point
it at attached storage or at a directory something else syncs, but that something else is yours to
set up. A backup on the same disk as the database defends against the database breaking and
against nothing else — not fire, not theft, not a failed disk, not a mistaken `rm`. For example:

```bash
# On the host, after configuring an rclone remote.
rclone sync /var/backups/dip remote:dip-backups --immutable
```

Set `BACKUP_OFFSITE_CONFIRMED=true` once that is real, to stop the reminder in the log. Setting it
without doing it only silences the message.

### Watching it work

```bash
C="docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml"

$C logs backup            # one runs at start-up, so problems surface immediately
ls -lh /var/backups/dip
```

A failed backup does not restart the container and does not stop anything else. It says
`FAILED` in the log, and nothing else will tell you. Until there is alerting, read this
occasionally.

### The restore drill

**A backup nobody has restored is not a backup**, and a procedure written in a document is not a
drill. There is a script:

```bash
sh infra/backup/restore-drill.sh /path/to/dip-20260806T023000Z.sql.gz.age ~/dip-backup.key
```

Run it **on your machine, not the server** — the private key lives with you, which is what makes
the encryption worth anything. It starts a throwaway PostgreSQL, restores into it, then checks
that the migrations, the tenants, the tables, the `dip_app` role and the row-level security
policies all came back, and destroys it. It touches nothing that is running.

The role and policy checks matter as much as the row counts. A dump restored without `dip_app`
leaves an application that cannot connect and security policies that refer to an account which
does not exist — and "the tables are all there" would not have told you.

Do this after the first deploy, and again whenever the schema changes shape. Not on the day you
need it.

### Proving the whole loop locally, before you depend on it

The backup and the restore can be exercised end to end against the **development** stack, with no
server involved. Do this once before trusting any of it, and again after changing the schema.

```bash
brew install age                       # or your platform's equivalent
docker compose -f infra/docker-compose.yml up -d
cd backend && ./gradlew bootRun        # once, so there is seeded data to back up
```

Then, from the repository root:

```bash
docker build -t dip-backup:test infra/backup

mkdir -p /tmp/dip-backups
age-keygen -o /tmp/dip-backup.key
PUB=$(grep 'public key:' /tmp/dip-backup.key | awk '{print $NF}')

# One backup, against the local database, straight to the script rather than the cron entrypoint.
docker run --rm \
  -e PGHOST=host.docker.internal -e PGPORT=55432 \
  -e PGUSER=dip -e PGPASSWORD=dip \
  -e BACKUP_DIR=/backups -e BACKUP_KEEP=7 \
  -e BACKUP_AGE_PUBLIC_KEY="$PUB" \
  -v /tmp/dip-backups:/backups \
  --entrypoint /usr/local/bin/backup.sh \
  dip-backup:test

sh infra/backup/restore-drill.sh "$(ls -t /tmp/dip-backups/*.age | head -1)" /tmp/dip-backup.key
```

The drill should report the migrations, tenants, tables, the `dip_app` role and the row-level
security policies, and finish with `RESTORE DRILL PASSED`. If it does, the backup path and the
restore path both work on real data rather than on a stub.

Clean up afterwards — that key and those archives are only a test:

```bash
rm -rf /tmp/dip-backups /tmp/dip-backup.key
```

### Restoring for real

Onto an empty stack, with the applications stopped:

```bash
C="docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml"

$C stop backend frontend keycloak
age -d -i dip-backup.key dip-20260806T023000Z.sql.gz.age \
  | gzip -d \
  | $C exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_OWNER_USER" -d postgres
$C start keycloak backend frontend
```

`ON_ERROR_STOP=1` is not optional. Without it psql continues past errors and leaves you with a
partially restored database that looks like it worked.

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

It must exit during start-up, not on the first request. That is what `src/instrumentation.ts`
is for: a container that exits immediately is caught by whoever deployed it, while they are still
watching. One that starts healthy and fails on the first real request is caught by a user, and
looks like an outage rather than a typo.

Note that Next.js prints `✓ Ready` **before** running the instrumentation hook, so the output
reads as though it started successfully and then changed its mind. It did not serve anything —
`Failed to prepare server` follows and the process exits. Judge it by the exit code, not by the
Ready line.

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
- **Nothing moves the backups off the host.** They are taken automatically, encrypted, on the
  schedule in `BACKUP_CRON` — but `BACKUP_HOST_DIR` is a bind mount and copying it somewhere else
  is yours to arrange. Until you have, a lost host is a lost database and a lost week.
- **Deployment is a person running a command.** CI publishes images from green builds only;
  nothing deploys them until somebody asks. `infra/redeploy.sh` makes that one line and does not
  make it automatic. There is no continuous deployment and no automated rollback.
- **No log aggregation and no alerting.** If something breaks at night, you find out in the
  morning. `docker logs` on the host is the whole story.
- **Migrations have no tested rollback path.** Backup first, every time.
- **Keycloak is configured by hand.** The realm is not in version control for a deployment, so
  rebuilding it is a manual exercise. Export the realm after configuring it and keep that export
  somewhere safe — without a client secret in it.
- **The application-role password rotation is manual**, for the Flyway reason above.

None of these stop a pilot. All of them matter before a telecom depends on it.
