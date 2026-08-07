# Dival Intelligence Platform — Delivery Plan

Living companion to [`ROADMAP.md`](ROADMAP.md). The roadmap says what to build in what order; this
records where we actually are, what is prioritized next, and which gaps are knowingly open.

Update it when a phase changes status or a gap is closed. Last reviewed: August 2026.

---

## 1. Current status

### Hardening — packaging and deployment

| Item | State |
|---|---|
| Container images | **Done.** Multi-stage builds for both applications: no JDK, Gradle or `node_modules` in the runtime layer, non-root users, pinned base image tags, health checks. Tests deliberately do not run inside the image build — they need Docker, and a build that silently skips its tests is worse than one that never had them. |
| Configuration without a rebuild | **Done.** `application-prod.yml` declares every value from the environment with **no defaults**, so a missing variable stops the boot. `ProductionSafety` then catches the harder case — a variable that is present and wrong — from an `EnvironmentPostProcessor`, before the context exists and therefore before anything can connect. The frontend does the same in `server/env.ts`, from the `instrumentation.ts` start hook. |
| Refusing a shared database account | **Done, and it is the check that matters most.** The migration user owns the schema and therefore *bypasses* row-level security. If the application ran as it, every isolation test in this project would still pass while every tenant saw every other. The backend will not start if the two accounts match. |
| Publishing images | **Done.** `release.yml` triggers on CI completion rather than on push, so it cannot publish from a red build. Images are tagged by full commit SHA, built once, scanned with Trivy, then pushed. The scan reports rather than blocks: a base-image CVE with no fix would otherwise stop the deployment carrying a security fix of our own. |
| A deployable stack | **Done.** `infra/docker-compose.deploy.yml`: nothing but Caddy publishes a port, Redis has a password, Keycloak has its own database and role, and every secret is required. |
| Images actually verified | **Done.** Both images build, and both refuse a deliberately bad configuration. Three defects were found this way and none of them were findable statically: `dip` is a Debian system group so the service account could not be created; Boot 3.3 replaced the layertools jarmode so the entrypoint named a launcher class that no longer exists; and the configuration guard was a bean, so Flyway's connection failed first and hid every other fault. |
| Automated backups | **Done.** `pg_dumpall` of both databases *and the roles*, encrypted to a public key so the host cannot read its own backups, pruned by count, on a cron. Eleven tests cover it by stubbing `pg_dumpall` — the failure paths are the ones that matter and the ones nobody exercises by hand, and they run in CI without a database. |
| Backups proved end to end | **Done, on real data.** A backup of the development database, encrypted, restored into a throwaway container: 38 tables, 33 policies, the `dip_app` role and the tenant rows all came back. The drill compares against the archive rather than the source tree, so it does not go stale — the first version failed a perfectly good backup because the database was one migration behind, which is a real question and somebody else's. |
| The restore drill | **Done.** A script that restores the latest archive into a throwaway container and checks the migrations, tenants, tables, the `dip_app` role and the row-level security policies all came back. Roles and policies are checked because "the tables are there" is a different claim from "the security is there". |
| The runbook | **Done.** `docs/DEPLOYMENT.md` covers first deploy, realm setup, upgrades, backups, rotating each secret including the one Flyway will not rotate for you, and a symptom table for when it breaks. It ends with what the deployment does **not** do. |

Still open, and named rather than implied:

- **No host.** Everything above is ready to run; nothing is running.
- ~~**No automated backups.**~~ **Closed.** An encrypted dump on a schedule, pruned by retention,
  with a restore drill that is a script rather than a paragraph. What remains is yours: pointing
  `BACKUP_HOST_DIR` at storage that is not the same disk, and copying the archives off the host.
- **Deployment is a person typing.** CI publishes; a human pulls. No continuous deployment, no
  automated rollback, and migrations have no tested down path.
- **No log aggregation, no alerting.** If it breaks at night, you find out in the morning.
- **Pre-signed download URLs** for file attachments are still not built.

### Hardening — partly done, deliberately paused

**Closed:** browser-held tokens, the oldest open gap in the project. Moved behind a
backend-for-frontend; see ADR 0003. Also closed a defect it exposed — response records
dereferencing lazy associations outside the transaction, which was a 500 on five screens that no
test could catch.

**Still open, to return to:**

- Pre-signed URLs for file downloads. Bytes are served through the API so authorization and the
  audit entry stay on one request; signed URLs need their own expiry and audit design and are
  only meaningful once storage is S3-compatible.
- No staging or production environment, and no CD pipeline. This is the largest remaining gap in
  Phase 0 and it grows more expensive with every module.


| Phase | Status | Notes |
|---|---|---|
| 0 — Foundation | **Largely complete** | Docs, repo, local environment, CI, design system, security baseline, ADRs 0001–0002. Missing: staging/production environments, CD pipeline. |
| 1 — Platform | **Complete** | Tenants, authentication, roles, organization structure, users, audit, EN/FR, notifications and file storage all in. |
| 2 — Core HR | **Complete** | Employees, reporting lines, contracts, dependents, emergency contacts, documents, expiry alerts, probation decisions and work patterns. |
| 3 — Recruitment & onboarding | **Complete** | Requisitions, candidates, applications, interviews, offers, the hire handover into Core HR, and onboarding/offboarding checklists. |
| 5 — Payroll | **Complete**, within a stated boundary | Effective-dated salaries, configurable pay components, payslips that reconcile by construction, and a sign-off path. **No statutory tax rates anywhere** — see `docs/PAYROLL_SCOPE.md`. |
| 4 — Time, performance, learning | **Complete** | Leave, attendance, work patterns, performance and learning are all in. Shift planning is deliberately out of scope and recorded below. |
| 5 — Payroll preparation | Not started | |
| 6 — Employee self-service | **Complete** | A portal under `/api/v1/me` that never accepts an employee id. Closes the `asSubject` gap: the server now decides who is asking. |
| 7 — Financial services | Not started | |
| 8 — Fraud intelligence | Not started | |
| 9 — Industry editions | **TIX partially built, out of order** | The telecom exchange was built ahead of its phase for commercial reasons. Subjects, identifiers, debt records, deterministic matching, inquiry API and UI exist. |
| 10 — Advanced AI | Not started | |

### What exists today

- **Backend** — 43 tables across 17 migrations, 15 modules (`attendance`, `employees`, `files`, `learning`, `leave`, `lifecycle`, `notifications`, `organizations`, `payroll`, `performance`, `recruitment`, `selfservice`, `tenants`, `tix`, `users`),
  ~180 endpoints, 358 passing tests. Cross-tenant isolation and row-level security are
  proven over raw JDBC as the unprivileged application role.
- **Frontend** — public landing page, authenticated product shell, and screens for the people
  directory, recruitment pipeline, onboarding and offboarding, leave balances, weekly
  attendance, performance, learning and compliance, payroll runs and payslips, organization
  structure, notifications and TIX verification. Bilingual throughout, with parity enforced in CI.
- **Scheduled work** — contract, document and certification expiry alerts, probation
  reminders, overdue checklist chasing, and monthly leave accrual.
- **Local environment** — one command brings up PostgreSQL, Redis and Keycloak with a realm,
  three fixture users across two tenants, seeded demo data for every module, and a scripted
  end-to-end check.

### Building TIX first — the trade-off we took

TIX belongs to Phase 9 but was built early to support the telecom opportunity. That was a
deliberate choice, and it leaves a hollow middle: the edition exists without the platform beneath
it. Section 2 is largely about refilling that middle before the gap compounds.

---

## 2. Priority now — tenancy and identity foundation

These three were treated as **one piece of work** because they touch the same layer.
**All three are now complete.** The tenancy and identity foundation is in place.

### P0.1 — Local user records and tenant membership — **done**

Delivered: `users` module with a tenant-owned `user_account` keyed by the OIDC `sub` claim,
just-in-time provisioning on first authenticated request, `GET /users/me`, a tenant-admin member
list, and `audit_event.actor_id` now pointing at a real record instead of a dangling subject.

Decisions worth remembering:

- **Keyed on `sub`, not email.** Email changes; a changed email must not orphan someone's history.
- **Stored roles are a display snapshot, not authority.** Permission checks read the access token,
  so a role revoked at the provider takes effect on the next request regardless of the stored row.
- **One identity maps to one tenant**, matching the single `tenant_id` claim the provider issues.
  A token whose claim disagrees with the stored record is refused rather than silently served.
  Multi-tenant membership needs a separate table and an ADR.
- **Provisioning lives in a service, not a filter**, so unauthenticated and health endpoints never
  touch the database, and concurrent first requests resolve through the unique constraint.

Verified: 20 backend tests pass, including six covering provisioning, idempotency, profile
refresh, tenant scoping and the mismatch refusal.

### P0.2 — Tenant provisioning — **done**

Delivered: `TenantService` with slug validation, uniqueness and audit; a `PLATFORM_ADMIN`-only
API under `/api/v1/platform/tenants`; and the local seeder rewritten as a caller of that service
instead of raw SQL, so there is no path that skips validation.

Decisions worth remembering:

- **Tenant identifiers are application-assigned**, which is what lets seeding and migration honour
  an id decided elsewhere while still going through the same service. `Persistable` is implemented
  so Spring Data does not read a non-null id as "existing" and issue a SELECT before every insert.
- **Deactivation keeps the row.** Users, audit entries and debt records reference it; deleting
  would orphan that history.
- **`provision()` is a genuine no-op when the id exists** — it does not overwrite, so restarts and
  re-runs are safe.
- **A platform administrator belongs to no tenant**, so nothing on this path may depend on a bound
  tenant context, and the audit actor may legitimately be null.

Verified: 34 tests pass; `infra/dev.sh check` covers 403 for a tenant admin, successful creation
by `platform-admin`, and 409 on a duplicate slug.

### P0.3 — Make row-level security bind — **done**

The application now connects as the unprivileged `dip_app` role, which cannot bypass RLS, and
`TenantAwareDataSource` binds each connection to its tenant at checkout. Migrations run
separately as the owner.

Decisions worth remembering:

- **The exchange needed a documented escape.** TIX exists to read debt records across operators,
  which RLS would otherwise forbid. The policy admits `app_exchange_mode()`, set with `SET LOCAL`
  inside the reading transaction so it is discarded at commit and cannot leak onto a pooled
  connection. It appears in `USING` but never in `WITH CHECK`: read across operators, never write
  outside your own tenant.
- **A transaction is pinned to one tenant**, because the binding happens at connection checkout.
  That matches one request, one tenant.
- **Spring integration tests still connect as the owner**, since several deliberately act as two
  tenants in a single transaction. They prove application-level scoping; `RowLevelSecurityTest`
  proves the policies themselves over plain JDBC as `dip_app`.

Verified: 26 tests pass, and `infra/dev.sh check` shows operator A receiving `OUTSTANDING_DEBT`
for a debt held by operator B — the exchange read going *through* the policy, not around it.

---

## 3. Phase 1 — complete

| Item | Why it matters |
|---|---|
| ~~Organization structure~~ | **Done.** Typed self-referencing tree (`org_unit`) with cycle prevention, cascading deactivation, per-tenant codes and a read-only screen. Reporting lines wait for employees. |
| ~~Notifications~~ | **Done.** Stored as message key plus parameters so a notification renders in the reader's language, not the raiser's. In-app channel; email and SMS become adapters over the same records. |
| ~~File storage~~ | **Done.** Content-type allowlist, size limit, SHA-256 checksum, randomised keys, audited reads, filesystem implementation behind a `FileStorage` interface so S3 drops in without a caller changing. |
| ~~Architecture guardrails in CI~~ | **Done.** `scripts/check_architecture.py` enforces four rules and runs in CI: no `common`→`modules` imports, no cross-module repository access, RLS on every tenant-owned table, no `CHAR(n)` columns. It has caught three real defects so far. |

**Phase 1 exit criteria** (from `ROADMAP.md`): two isolated tenants, no cross-tenant access,
bilingual UI, security tests passing. **All four hold** — isolation is enforced by the database as
well as by application code.

Deferred deliberately, each with a reason rather than an oversight:

| Deferred | Why |
|---|---|
| Pre-signed URLs for downloads | Bytes are served through the API so authorization and the audit entry stay on the same request. Signed URLs need their own expiry and audit design, and are only meaningful once storage is S3-compatible. |
| Malware scanning on upload | Needs a scanning service; the allowlist, size cap and checksum are in place to build on. |
| Email and SMS delivery | Adapters over the notification records that already exist. Keeping the record independent of delivery means nothing is lost when a provider is down, and nothing is shown twice when it recovers. |

---

## 4. Phase 2 — complete

| Item | Why it matters |
|---|---|
| ~~Employees and contracts~~ | **Done.** `employee` and `employment_contract`, with a loop check on reporting lines, one active contract per person enforced by a partial unique index, and termination that closes the running contract in the same step. |
| ~~Employee records~~ | **Done.** Dependents, emergency contacts and documents, the last built on the Phase 1 file storage rather than a second upload path. |
| ~~Expiry alerts~~ | **Done.** A scheduled scan raises notifications for contracts and documents approaching expiry, once per record via `expiry_notified_at`, and leaves the alert unsent when there is nobody to send it to rather than silently marking it handled. |

---

## 5. Phase 3 — recruitment

| Item | State |
|---|---|
| Requisitions | **Done.** Draft → pending approval → approved → open, with the approver recorded separately from the actor: an approval whose authoriser is inferred from an audit log is not an approval. Headcount is decremented on hire and the requisition closes itself when it is met. |
| Candidates | **Done.** A person, not an application — registration is idempotent on email, so three applications from the same address are one candidate and "have we spoken to them before" is answerable. |
| Applications | **Done.** Legal transitions encoded in `ApplicationStatus.canFollow`; a rejection must carry a reason, because a pipeline that cannot say why people were turned down cannot be reviewed for bias. |
| Interviews | **Done.** One row per interviewer rather than one shared verdict, so a dissenting voice survives. Feedback is what completes an interview; one marked done with nothing written down is indistinguishable later from one that never happened. |
| Offers and the hire | **Done.** `OfferService` sits apart from `RecruitmentService` because acceptance crosses into Core HR. One transaction covers the offer, the application, the requisition headcount, the employee and their first contract — a hire that half-happens leaves somebody starting on Monday with no contract. The contract is drafted, not activated: it takes effect on the agreed start date. |

| Onboarding and offboarding | **Done.** Templates are copied, not referenced: editing a template next year must not rewrite what somebody was actually asked to do last year. A checklist cannot be closed while a mandatory step is outstanding — being able to tick "offboarding complete" over an unrevoked building pass is the failure the table exists to prevent. Blocking or skipping a step requires an explanation. |
| Probation | **Done.** Confirm, extend or fail, recorded against the contract with the author and the moment. An unconfirmed probation quietly becomes a confirmed one in most jurisdictions, so silence still decides the outcome — it just leaves nobody who can be shown to have decided it. A failed probation ends the employment in the same transaction. |
| Overdue and probation alerts | **Done.** The HR scan gained a probation sweep (CRITICAL, and on a shorter window than contract expiry, because the decision has to land *before* the period ends). Checklist reminders live in their own scanner inside the lifecycle module rather than reaching into another module's repository, and go to the step's assignee first — an alert addressed to everybody is addressed to nobody. |

Still open in Phase 3:

- candidate retention rules per country — **required before production.** Candidates are the only
  people in the platform with no relationship to the employer, most will be rejected, and their
  data should not linger indefinitely.
- checklist items cannot yet be ticked off from the UI; the screen is read-only and the endpoint
  is there, so this is a form, not a design question.

---

## 6. Phase 4 — time and leave

Chosen ahead of performance and learning because payroll in Phase 5 cannot be built without leave
balances, and because it is the feature HR staff touch every day.

| Item | State |
|---|---|
| Working-day arithmetic | **Done.** Weekends and per-tenant public holidays are not charged, half days come off either end, and a half day marked on a Saturday takes nothing off — it would otherwise subtract half a day that was never charged. This is the arithmetic people check, and getting it wrong quietly takes days from them. |
| Balances and ledger | **Done.** A running total alongside an append-only ledger. The total is what the overdraft check reads; the ledger is why it says what it says. `leave_ledger_entry` has no UPDATE grant — corrections are further entries, never edits, because a balance that can be quietly rewritten is worth nothing in a dispute. |
| Requests and approvals | **Done.** Days are reserved on **submission**, not approval: two pending requests that each fit the balance could otherwise both be approved, and the person finds out months later. Nobody approves their own leave. A refusal must carry a reason. Leave that has already started cannot be cancelled away — that is a correction somebody makes deliberately. |
| Accrual | **Done.** The monthly job tops people up to what they should have by now rather than adding a slice and hoping it ran exactly once. Running twice adds nothing; a missed month catches up on its own. A mid-year joiner accrues from their hire date. |
| Carryover | **Done.** Capped, with the lapsed remainder written as its own ledger entry. Somebody who loses six days at year end is entitled to see that it happened. |

| Work patterns | **Done**, and it closed a real defect. The working week was tenant-wide configuration, so somebody on four days was charged five for a week off *and* accrued a full entitlement — a quarter of their leave taken by a default. Each employee can now carry a pattern giving every weekday a fraction. Both sides scale: four fifths of the entitlement, four days for a week off, so a part-timer gets the same number of weeks away as anybody else. A half day comes off in proportion to the day worked, because half of half a day is a quarter. |

Known limits, recorded rather than hidden:

| Attendance | **Done.** Clock in and out, shifts recorded after the fact, and corrections that supersede rather than overwrite — attendance is what people are paid from and disciplined against, so "what did it say before?" has to be answerable. Overlaps are refused in the service and by a partial unique index: two entries covering one hour is the most expensive mistake this table can hold. |
| Timesheets and overtime | **Done.** A period totalled against the employee's own pattern, with approved leave and public holidays counted as owed but not as absence — a sheet that read approved leave as absence would turn every holiday into a disciplinary conversation. Figures are frozen at submission so a payslip and the screen justifying it cannot drift. Overtime is stated in minutes and deliberately **not priced**: what an hour is worth is a payroll decision, and a multiplier here would mean two systems disagreeing the first time a rate changed. |

Known limits, recorded rather than hidden:

- A leave year is a calendar year. A tenant whose year starts in April cannot be served yet;
  requests crossing 31 December are refused rather than silently split.
- Timesheet periods are weekly, Monday to Sunday. Fortnightly and monthly are the same arithmetic
  on a different anchor, left until somebody needs them rather than guessed at.
- Shift planning — rosters, who is meant to be on which shift — is not built. Attendance records
  what happened, not what was scheduled.
- Overtime is one number. Jurisdictions that distinguish night, weekend and public-holiday rates
  need those split out, and that is a payroll conversation.
- Work patterns have no history. Changing somebody's pattern changes future requests and future
  accrual but leaves past requests at the days they were charged — correct, but it means a
  mid-year change to a part-time contract does not pro-rate the year they are in. That needs
  effective-dated patterns, which payroll will want anyway.
- Work patterns can be created and assigned through the API but have no screen yet.

---

## 7. Phase 5 — payroll

Built on leave and attendance, which is why Phase 4 came first: a payslip that cannot see unpaid
leave or overtime is a payslip somebody has to correct by hand.

| Item | State |
|---|---|
| Effective-dated salaries | **Done.** A raise is a new row, not an edit, and a partial unique index allows only one open-ended row per person. Two rows with no end date is how somebody gets paid twice, or paid whichever figure the query happened to reach first. The salary used is the one in force on the **last day** of the period. |
| Pay components | **Done.** Fixed amounts, percentage of basic, percentage of gross, per hour, and manual. Configured per tenant and assigned per person with an optional override, both effective-dated. Components are retired, never deleted — a payslip must still explain itself years later. |
| Payslips that reconcile | **Done.** `addLine` is the only route an amount takes onto a payslip and it re-totals from the lines every time, so the document reconciles by construction rather than by discipline. The database enforces the same rule: `CHECK (net_pay = gross_earnings - total_deductions)`. Every line records *how* its figure was reached, in words, because a payslip nobody can check is a payslip nobody should trust. |
| Order of application | **Done.** Earnings, then employer contributions, then deductions. A percentage-of-gross deduction therefore sees every earning and no other deduction, which makes the result independent of the order rows happened to be inserted. |
| Rounding | **Done.** Half-up at each line, once. Rounding only the total would produce a document whose lines do not sum to it. |
| Sign-off | **Done.** Calculate, approve, reopen, mark paid. Nobody approves their own run. Reopening clears the approval rather than keeping a stale one. An approved period is frozen — the entity refuses edits, not just the service. |
| Missing salary | **Done.** Reported by name, never guessed. `calculate` returns the payslips it produced *and* the people it skipped, so an incomplete run is visible instead of quietly short. |

### The line we drew, deliberately

`docs/PAYROLL_SCOPE.md` states it where people will read it, and it is worth repeating here:

> There is no income tax table, no social security schedule and no set of thresholds anywhere in
> this module. That is a deliberate refusal, not an unfinished feature.

The platform applies whatever components an accountant configures. It does not decide what the
DRC's rates are, and it must not be the place somebody looks one up. **Before a real pay run in
any jurisdiction, the component configuration must be signed off by a qualified payroll
practitioner for that jurisdiction.** The screen says so too, not only the documentation.

Also deliberately absent, and recorded rather than hidden:

- **No payment disbursement.** Nothing moves money. Bank files and mobile-money integration are a
  separate decision with separate consequences.
- **No proration.** A mid-period joiner or leaver is paid the full period. That is wrong for them
  and is left visibly unimplemented rather than approximated.
- **No retrospective corrections.** A mistake found after payment is fixed by an adjustment in the
  next period. There is no mechanism to reissue a past payslip.
- **Overtime is priced only where a per-hour component exists.** Night, weekend and public-holiday
  differentials are not modelled.
- **Payslip documents are not generated.** The figures are there; a PDF a person can be handed is not.

---

## 8. Phase 6 — employee self-service

| Item | State |
|---|---|
| Identity from the token | **Done.** `CurrentEmployee` maps the authenticated subject to a user account to an employee. Every `/api/v1/me` endpoint resolves the person that way, and **none of them accept an employee id**. |
| The portal | **Done.** Profile, payslips, leave balances, booking and cancelling leave, timesheets, attendance, goals, reviews and training — each scoped to the caller. |
| Manager's team | **Done.** Direct reports, with names and status and nothing else. Empty for most people, which is an answer rather than an error. |
| Narrower responses | **Done.** Self-service has its own response records rather than reusing the HR ones. They cannot grow a field because an HR screen wanted one. |
| The `asSubject` gap | **Closed.** See below. |

### Why no endpoint takes an employee id

The usual shape is to accept an id and check it belongs to the caller. That works until somebody
adds a second endpoint, or moves the check behind a condition, or writes a new one by copying an
old one and dropping the check. A parameter that must be validated is a parameter that will
eventually be used without validation.

Three endpoints do take an id — cancelling leave, submitting a timesheet, reading one payslip —
because the id names a record rather than a person. All three go through a single `mine` helper,
so a new one either uses it or is visibly missing it. The refusal does not distinguish "not
yours" from "not there", because a difference between those two enumerates other people's
records.

### The access rule the caller used to choose

The performance API carried an `asSubject` request parameter deciding how much of a review to
fill in. Anyone who wanted a reviewer's unshared rating only had to ask for it with the flag
turned off. It is gone: `CurrentEmployee.isSelf` compares the caller's own employee record to the
review's subject, server-side. The same now decides whether anonymous feedback shows its author.

### Two things deliberately withheld

- **A payslip from a run that has not been signed off.** `PeriodStatus.isVisibleToEmployee` is
  true only once payroll has approved it. A calculated run is still being checked, and showing
  somebody a net figure that is about to be corrected starts a conversation that cannot be taken
  back.
- **A review that has not been shared.** Enforced by the entity, as before, not by the route.

### Local fixture change

The Keycloak realm users now carry fixed ids, so a sign-in can be seeded against a particular
person. **After pulling this you must recreate the container**, or the ids will be whatever the
first import generated:

```
docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak
```

`LocalSelfServiceSeeder` then links `operator-a` to the director and `no-roles` to an engineer —
the second on purpose, because a portal that has only ever been opened by an administrator has
not been tested. Writing a user account nobody has authenticated as is something an identity
provider exists to prevent; it is acceptable only because these are development fixtures, and the
seeder says so.

---

## 9. Security

A three-part adversarial review in August 2026 found that any signed-in employee could read and
rewrite a colleague's performance review — including the reviewer's unshared assessment, which is
more than the subject is allowed to see — download every document in the tenant including sick
notes and identity scans, and book or cancel other people's leave. Cross-tenant isolation held
throughout; the failure was entirely horizontal, inside a tenant.

The root cause was one line: `SecurityConfig` authenticates every request and authorizes none, so
an endpoint without `@PreAuthorize` was reachable by everybody. **That is now a build failure**
(`check_architecture.py` rule 5), and `AuthorizationBoundaryTest` proves the refusals bite rather
than merely existing.

`docs/SECURITY_REVIEW.md` has every finding with its current status.

**Every Critical and High finding is closed, and each fix has a test that fails without it.** So
are most Mediums, including all four TIX findings — the confidence score that leaked a
competitor's subject's name one token at a time, the weak-identifier bypass, the missing rate
limit, and an audit trail that validated a `purpose` and then discarded it.

Seven remain, all Medium or Low: JWT audience validation (not reachable in the shipped topology,
where the backend publishes no port), upload limits, the `IllegalArgumentException` message
handler, and four defence-in-depth items.

**"No known Critical or High findings" is not the same as "secure".** Three reviewers went over
code written by one author and found what they found; a fourth pass would find more. What makes
the next round smaller is structural rather than any single fix in that list: omission fails the
build, something asks every question from the wrong side, and the audit trail records why.

---

## 10. Then — TIX depth

TIX can now be built on a real foundation:

- declaration endpoint — currently records can be settled and disputed but not created via API
- dispute workflow with the suppression rules described in `TIX_MODULE.md`
- subject rights: access, rectification, erasure
- business verification: RCCM, tax number, directors
- retention and automatic delisting

---

## 11. Known open gaps

| Gap | Severity | Where |
|---|---|---|
| Spring integration tests connect as the schema owner | Low — deliberate, and RLS is covered separately | `AbstractIntegrationTest`, ADR 0002 |
| ~~Tokens held in browser session storage~~ | **Closed.** Moved behind a backend-for-frontend; see ADR 0003. | `frontend/src/server/` |
| Cookie-borne session invites CSRF | Low — `SameSite=Lax` plus an explicit `Origin` check at the proxy | ADR 0003, `api/proxy` |
| Integration tests are `@Transactional`, so anything that only fails at commit succeeds in them | Medium — it hid a 500 on five screens, then hid a payroll run that reported success and failed on commit | `ResponseMappingTest` commits and has now caught two distinct defect classes; new response records need adding to it |
| Self-service has no profile editing: people can read their record but not correct it | Low — a change of address is an HR conversation for now | Section 8 |
| Payroll has no proration, no corrections and no payslip document | Medium — stated in `PAYROLL_SCOPE.md` and on the screen, not hidden | Section 7 |
| Pay component rates are unverified by anyone qualified | **High before production.** The platform holds no rates of its own; whatever a tenant configures is what people are paid. | `docs/PAYROLL_SCOPE.md` |
| ~~No staging or production environment, no CD~~ | **Partly closed.** Both applications are packaged, CI publishes images to GHCR from green builds only, and there is a deployable stack with a runbook — see `docs/DEPLOYMENT.md`. What remains is a host to run it on, automated backups, and deployment that does not involve a person typing. | `infra/docker-compose.deploy.yml` |
| `AGENTS.md` rules unenforced by CI | Medium — rules decay silently | Section 3 |
| TIX has no declaration API | Medium | Section 4 |
| Keycloak realm is a development fixture | Low until deployment | `infra/keycloak/realm-dip.json` |

---

## 12. Working agreement

- Every tenant-owned table adds its RLS policy in the same migration that creates it.
- A module is not done until a cross-tenant isolation test proves the boundary holds.
- Message keys land in `en.json` and `fr.json` in the same change.
- AI output stays advisory; alerts are indicators requiring review, never findings.
- Claims about tests are only made when the tests were actually run.
- Every endpoint states who may call it, and something asks from the wrong side.
