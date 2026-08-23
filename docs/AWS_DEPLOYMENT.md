# Standing DIP up on AWS

The AWS-specific half of [DEPLOYMENT.md](DEPLOYMENT.md). That document says what the stack is and
how to operate it, and applies unchanged here. This one covers only what is particular to running
it on EC2 at `dip.dival.ai`: the instance, the addresses, the DNS, and getting backups off the
machine.

Read DEPLOYMENT.md first. Nothing here repeats it.

**What this is for.** A testing environment partners can reach, that you can push to several
times a day. It is one instance running the same Docker Compose stack as a laptop, with real TLS
and a real hostname. It is not a production posture and the limitations at the end of
DEPLOYMENT.md all still apply — most importantly, **losing the instance loses the service** until
you rebuild it from a backup.

---

## 1. The instance

| | |
|---|---|
| AMI | Ubuntu Server 24.04 LTS (x86_64) |
| Type | **t3.large** (2 vCPU, 8 GB) |
| Storage | 30 GB gp3, encrypted |
| Address | **Elastic IP**, allocated and associated |

**On the size.** DEPLOYMENT.md says two vCPUs and 4 GB is enough to start, and it is — for the
application. This box also runs Keycloak, PostgreSQL, Redis and Caddy, and Keycloak alone will
take a gigabyte without being asked. On `t3.medium` the stack fits and then swaps under a demo,
which reads to a partner as "the product is slow". `t3.large` is roughly $60/month on demand in
`eu-west-1` and is the cheapest thing that will not embarrass you in a meeting. Drop to
`t3.medium` if it must be cheaper, and add 2 GB of swap if you do.

**On the Elastic IP.** Not optional. A default public IP changes when the instance stops and
starts, and DNS would then point at somebody else's machine until you noticed. An Elastic IP
attached to a running instance is free; one allocated and left unattached is billed, which is the
right way round.

**On the disk.** 30 GB carries the database, seven encrypted archives and a few generations of
container images. Docker images are what actually fills it — every redeploy leaves the previous
ones behind. `docker image prune -af --filter "until=168h"` monthly, or on the day it fills.

### Security group

| Rule | Port | Source |
|---|---|---|
| SSH | 22 | **Your IP only**, as `x.x.x.x/32` |
| HTTP | 80 | `0.0.0.0/0` |
| HTTPS | 443 | `0.0.0.0/0` |

Nothing else, in either direction beyond the defaults. Port 80 is open because Let's Encrypt
uses it to prove you control the name, not because anything is served over it — Caddy redirects.

Do not open 5432, 6379 or 8081 "just for testing". A PostgreSQL port open to the internet is
found by a scanner within hours, and this database will hold partner data.

---

## 2. DNS

Two records, both pointing at the Elastic IP:

```
dip.dival.ai   A   <elastic ip>
id.dival.ai    A   <elastic ip>
```

**If `dival.ai` is in Route 53**, add them in that hosted zone. Leave TTL at 300 while you are
setting up — a wrong record at 86400 is a day of waiting.

**If it is at a registrar** (Namecheap, GoDaddy, Cloudflare, wherever the domain was bought),
add them there. Moving the whole domain into Route 53 is not necessary for this and is a change
with its own failure modes; two A records at the existing provider is the smaller step.

**If it is behind Cloudflare**, set both records to **DNS only** — grey cloud, not orange. Proxied
records terminate TLS at Cloudflare, Caddy's certificate request then fails, and the symptom is a
redirect loop that looks like an application fault.

### Both names must resolve before you start the stack

Caddy requests certificates on first boot. If a name does not resolve yet it fails, backs off and
retries, **and Let's Encrypt rate-limits failures** — five per hostname per hour. Getting this
wrong costs an hour of not being able to try again, which is a bad hour to spend in front of
partners.

Check from somewhere that is not the instance:

```bash
dig +short dip.dival.ai
dig +short id.dival.ai
```

Both must print the Elastic IP. Then start.

---

## 3. The host

SSH in, then:

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo tee /etc/apt/keyrings/docker.asc > /dev/null
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker $USER && newgrp docker
```

The firewall, even though the security group already does this. Two layers cost nothing and the
security group is the one somebody edits in a hurry:

```bash
sudo ufw allow OpenSSH && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable
```

Unattended security updates, because this box will run for months without anybody thinking
about it:

```bash
sudo apt-get install -y unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades
```

### Pulling private images

The GHCR packages are private unless you made them public. Create a **classic** personal access
token with `read:packages` only — nothing else, no `repo` — and log in on the instance:

```bash
echo "<token>" | docker login ghcr.io -u <your-github-username> --password-stdin
```

The credential is stored at `~/.docker/config.json` on the box, base64-encoded and not encrypted.
That is a token that can read your private packages sitting on an internet-facing host: give it
`read:packages` and nothing more, and set it to expire. A token that can also read source, or
write anything, does not belong here.

---

## 4. First deploy

Follow **DEPLOYMENT.md § First deploy**. The AWS-specific values in `infra/deploy.env`:

```ini
# Lowercase, even though the repository is not. A registry rejects a mixed-case path, and the
# publishing side lowercases it silently — so the images are at the lowercase path and this is
# the only place the two can disagree.
GITHUB_REPOSITORY=alaincizungu/divalpeople

SITE_HOSTNAME=dip.dival.ai
KEYCLOAK_HOSTNAME=id.dival.ai
SITE_URL=https://dip.dival.ai
OIDC_ISSUER=https://id.dival.ai/realms/dip

# prod,demo to seed the exchange. See § 7.
SPRING_PROFILES_ACTIVE=prod,demo

BACKUP_HOST_DIR=/var/backups/dip
```

```bash
sudo mkdir -p /var/backups/dip && sudo chown $USER /var/backups/dip

# Uploaded files. Owned by uid 10001 — the unprivileged account inside the backend container, not
# a user on this host. Without the chown the application starts, reaches the file-storage bean and
# exits with an access-denied error from a constructor, minutes into a boot that looked healthy.
sudo mkdir -p /var/lib/dip/files && sudo chown 10001:10001 /var/lib/dip/files
```

Then the realm:

```bash
sh infra/keycloak/setup-realm.sh
```

It builds the realm, the client, the `tenant_id` mapper, every role in `Roles.java` and one account
for you, and prints the client secret at the end — put that in `OIDC_CLIENT_SECRET` and restart the
frontend. It talks to the admin API over the container's own loopback, so it needs no tunnel and
cannot be blocked by the proxy. Re-running it is safe.

**Do not use the admin console for this.** It is served at whatever `hostname-admin` says,
authenticates against the master realm, and the proxy blocks `/realms/master*` on the public name
on purpose — so it loads from one origin and signs in at another. That cost most of a day and
produced nothing the script does not.

**Do not import `infra/keycloak/realm-dip.json`.** It is a development fixture with a known client
secret and four users whose passwords are in the repository. On a public hostname that is not a
shortcut, it is an open door.

### If every screen says "Authentication is required"

Sign-in works, the backend is up, and every screen is empty with a 401. The account is missing its
`tenant_id` attribute, so the access token carries no tenant claim and `TenantResolutionFilter`
refuses the request before any handler sees it.

Keycloak 26 always runs the declarative user profile, and an attribute the profile does not declare
is **discarded on write without an error** — creating a user with `attributes.tenant_id=[...]`
returns 201 and stores nothing. `setup-realm.sh` now sets `unmanagedAttributePolicy=ADMIN_EDIT`
first and reads the attribute back afterwards, so a realm built by the current script cannot land
in this state. A realm built before that fix can. To check:

```bash
docker compose --env-file infra/deploy.env -f infra/docker-compose.deploy.yml \
  exec -T keycloak /opt/keycloak/bin/kcadm.sh get users -r dip \
  -q username=<your username> --fields username,attributes
```

An empty `attributes` is the fault. Either re-run `setup-realm.sh`, or set the policy and the
attribute by hand. **Then sign out completely and sign in again** — an existing session holds a
token minted before the attribute existed, and nothing about it changes until it is replaced.

---

## 5. Deploying a new version

```bash
sh infra/redeploy.sh <40-character commit sha>
```

Backs up, pulls while the old version keeps serving, swaps, waits for the frontend to answer, and
prints the line that puts it back. It refuses `latest` and refuses short SHAs, and it stops
without deploying anything if the backup fails — migrations run automatically and this project has
no down-migrations, so that archive is the only way out of a bad one.

To find the newest published build:

```bash
gh api repos/<owner>/dip/actions/workflows/release.yml/runs \
   --jq '.workflow_runs[0] | "\(.head_sha)  \(.conclusion)  \(.created_at)"'
```

Images are published only from a commit that passed CI, so any SHA that appears there is a green
build. Going back is the same command with the previous SHA — which the script prints, and also
leaves in `infra/.last-deploy` — **but only if no migration ran in between**. If one did, the old
image cannot read the new schema and the archive is the way back.

---

## 6. Getting backups off the instance

The stack encrypts an archive every night into `/var/backups/dip`. Nothing moves it anywhere. A
backup on the same EBS volume as the database defends against the database breaking and against
nothing else — not a terminated instance, not a deleted volume, not a mistaken `rm`.

**An S3 bucket, and an instance role rather than an access key.** A key on the box is a credential
that leaves with the box; a role is scoped to the instance and rotates itself.

1. Create a bucket — `dival-dip-backups`, versioning on, public access blocked, default
   encryption on.
2. Create an IAM role for EC2 whose policy allows `s3:PutObject` and `s3:ListBucket` on that
   bucket **and nothing else**. Not `s3:*`, and not `GetObject`: this host needs to write
   backups, never to read them back. A host that can read the bucket is a host that hands over
   every archive along with itself, which is most of what encrypting them was for.
3. Attach it to the instance.
4. On the box:

   ```bash
   sudo apt-get install -y awscli
   aws s3 sync /var/backups/dip s3://dival-dip-backups/ --no-progress
   ```

5. Once a day, after the backup at 02:30:

   ```bash
   (crontab -l 2>/dev/null; echo "0 3 * * * aws s3 sync /var/backups/dip s3://dival-dip-backups/ --no-progress") | crontab -
   ```

6. Set `BACKUP_OFFSITE_CONFIRMED=true` in `deploy.env` to stop the reminder — once this is real,
   and not before.

**The age private key does not go on this instance, or in this bucket.** It lives with you, in a
password manager. That is the entire reason the archives are encrypted: an attacker who takes the
instance takes the ciphertext and stops there. Losing the key loses every backup, permanently, so
keep a second copy somewhere independent.

**EBS snapshots are not a substitute.** A snapshot of a running database is a copy taken
mid-write; it usually restores and sometimes does not, and you find out which on the day it
matters. Take them as well if you like — they are cheap and they recover a whole instance in
minutes — but the archive that has been through `infra/backup/restore-drill.sh` is the backup.

---

## 7. Before partners log in

- **Run the restore drill once, on your laptop, with a real archive from this instance.** It is in
  DEPLOYMENT.md. A backup nobody has restored is not a backup, and the cheapest day to discover
  that is before anybody depends on the box.
- **Disable the bootstrap Keycloak admin** once a named administrator exists.
- **One Keycloak account per partner**, each with its own `tenant_id`. Shared logins make the
  audit trail — which is a feature of this product — say nothing.
- **Tell them what it is.** A test environment on one instance with no redundancy, no alerting,
  and manual deploys. If they load real customer records into it, they should know the boundary
  they are loading them across.

### Seeding the exchange

A freshly deployed instance is empty, and an empty registry does not look new — it looks broken.
Every count is zero, and the exchange, whose whole point is that more than one institution has
contributed, demonstrates nothing.

In `infra/deploy.env`:

```ini
SPRING_PROFILES_ACTIVE=prod,demo
```

`prod,demo`, never `demo` alone. The demo profile carries no datasource, no issuer and no
connection settings; it says only what is in the database at start-up.

On the next start you get two operators, six other participants, and two books of businesses aged
across every band in two currencies — including one business owing both operators, which is the
case the exchange exists for. Every institution name carries `(demo)`, so a screenshot that
circulates cannot be mistaken for a real bank's position.

**It seeds data and never an identity.** No sign-in is created. The tenant ids are fixed
constants, so an account you create by hand in Keycloak carrying
`tenant_id=11111111-1111-1111-1111-111111111111` opens the first seeded book, and
`22222222-2222-2222-2222-222222222222` the second. The realm fixture in `infra/keycloak/` still
must never be imported — its passwords are in the repository.

**Nothing personnel-shaped is seeded.** No employees, payroll, leave, attendance, performance,
learning or self-service. Those seeders invent salaries, national identifiers and logins wired to
fixture accounts, and none of that belongs on a public host. `DemoProfileScopeTest` fails the build
if anybody adds a seeder to the profile, because that edit is two words long and looks like
configuration when it is a disclosure decision.

The backend logs a warning on every start while this is on. Read it once — it is the only thing
that will tell a colleague, six months from now, that the eight institutions in this database were
invented.

**Turning it off does not remove the data.** Seeding is idempotent and skips what already exists;
dropping `demo` from the variable stops it adding more and leaves what is there. Clearing it is a
deliberate act, so plan on rebuilding the database before this instance holds anything real.

---

## 8. Roughly what it costs

`eu-west-1`, on demand, per month:

| | |
|---|---|
| t3.large | ~$60 |
| 30 GB gp3 | ~$3 |
| Elastic IP (attached) | $0 |
| S3, a few GB with versioning | ~$1 |
| Data transfer out, light testing | a few dollars |

Call it **$65–75/month**. A one-year no-upfront reserved instance or a Savings Plan takes the
compute to roughly $38 if this box is going to be there for a year, which for a partner testing
environment it probably is.
