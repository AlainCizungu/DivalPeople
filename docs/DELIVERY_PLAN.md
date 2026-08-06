# Dival Intelligence Platform — Delivery Plan

Living companion to [`ROADMAP.md`](ROADMAP.md). The roadmap says what to build in what order; this
records where we actually are, what is prioritized next, and which gaps are knowingly open.

Update it when a phase changes status or a gap is closed. Last reviewed: August 2026.

---

## 1. Current status

| Phase | Status | Notes |
|---|---|---|
| 0 — Foundation | **Largely complete** | Docs, repo, local environment, CI, design system, security baseline, ADRs 0001–0002. Missing: staging/production environments, CD pipeline. |
| 1 — Platform | **Complete** | Tenants, authentication, roles, organization structure, users, audit, EN/FR, notifications and file storage all in. |
| 2 — Core HR | **Complete** | Employees, reporting lines, contracts, dependents, emergency contacts, documents, and expiry alerts for both contracts and documents. |
| 3 — Recruitment & onboarding | **Next** | Requisitions, candidates, interviews, offers, onboarding checklists. |
| 4 — Time, performance, learning | Not started | |
| 5 — Payroll preparation | Not started | |
| 6 — Employee self-service | Not started | |
| 7 — Financial services | Not started | |
| 8 — Fraud intelligence | Not started | |
| 9 — Industry editions | **TIX partially built, out of order** | The telecom exchange was built ahead of its phase for commercial reasons. Subjects, identifiers, debt records, deterministic matching, inquiry API and UI exist. |
| 10 — Advanced AI | Not started | |

### What exists today

- **Backend** — 14 tables (`tenant`, `audit_event`, `user_account`, `tix_subject`,
  `tix_subject_identifier`, `tix_debt_record`, `org_unit`, `notification`, `stored_file`, `employee`, `employment_contract`, `employee_dependent`,
  `employee_emergency_contact`, `employee_document`), 7 modules (`tenants`, `users`, `organizations`, `notifications`, `files`,
  `employees`, `tix`), 39 endpoints,
  59 passing tests, including cross-tenant isolation and row-level security proven
  over raw JDBC as the unprivileged application role.
- **Frontend** — public landing page, authenticated product shell, people directory,
  organization structure, notifications and TIX verification screens,
  bilingual throughout with parity enforced in CI.
- **Local environment** — one command brings up PostgreSQL, Redis and Keycloak with a realm,
  three fixture users across two tenants, and a scripted end-to-end check.

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

## 6. Then — TIX depth

TIX can now be built on a real foundation:

- declaration endpoint — currently records can be settled and disputed but not created via API
- dispute workflow with the suppression rules described in `TIX_MODULE.md`
- subject rights: access, rectification, erasure
- business verification: RCCM, tax number, directors
- retention and automatic delisting

---

## 5. Known open gaps

| Gap | Severity | Where |
|---|---|---|
| Spring integration tests connect as the schema owner | Low — deliberate, and RLS is covered separately | `AbstractIntegrationTest`, ADR 0002 |
| Tokens held in browser session storage | Medium — production should use a backend-for-frontend | `frontend/src/auth/config.ts` |
| No staging or production environment, no CD | Medium | Phase 0 remainder |
| `AGENTS.md` rules unenforced by CI | Medium — rules decay silently | Section 3 |
| TIX has no declaration API | Medium | Section 4 |
| Keycloak realm is a development fixture | Low until deployment | `infra/keycloak/realm-dip.json` |

---

## 6. Working agreement

- Every tenant-owned table adds its RLS policy in the same migration that creates it.
- A module is not done until a cross-tenant isolation test proves the boundary holds.
- Message keys land in `en.json` and `fr.json` in the same change.
- AI output stays advisory; alerts are indicators requiring review, never findings.
- Claims about tests are only made when the tests were actually run.
