# Dival Intelligence Platform — Delivery Plan

Living companion to [`ROADMAP.md`](ROADMAP.md). The roadmap says what to build in what order; this
records where we actually are, what is prioritized next, and which gaps are knowingly open.

Update it when a phase changes status or a gap is closed. Last reviewed: August 2026.

---

## 1. Current status

| Phase | Status | Notes |
|---|---|---|
| 0 — Foundation | **Largely complete** | Docs, repo, local environment, CI, design system, security baseline, ADRs 0001–0002. Missing: staging/production environments, CD pipeline. |
| 1 — Platform | **~80%** | Authentication, role mapping, audit, EN/FR, tenants, users and organization structure are in. Notifications and file storage are not. |
| 2 — Core HR | Not started | **Unblocked** — users and organization structure now exist. |
| 3 — Recruitment & onboarding | Not started | |
| 4 — Time, performance, learning | Not started | |
| 5 — Payroll preparation | Not started | |
| 6 — Employee self-service | Not started | |
| 7 — Financial services | Not started | |
| 8 — Fraud intelligence | Not started | |
| 9 — Industry editions | **TIX partially built, out of order** | The telecom exchange was built ahead of its phase for commercial reasons. Subjects, identifiers, debt records, deterministic matching, inquiry API and UI exist. |
| 10 — Advanced AI | Not started | |

### What exists today

- **Backend** — 7 tables (`tenant`, `audit_event`, `user_account`, `tix_subject`,
  `tix_subject_identifier`, `tix_debt_record`, `org_unit`), 4 modules (`tenants`, `users`, `organizations`, `tix`), 20 endpoints,
  34 passing tests, including cross-tenant isolation and row-level security proven
  over raw JDBC as the unprivileged application role.
- **Frontend** — public landing page, authenticated product shell, organization structure and
  TIX verification screens,
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

## 3. Next — completing Phase 1

| Item | Why it matters |
|---|---|
| ~~Organization structure~~ | **Done.** Typed self-referencing tree (`org_unit`) with cycle prevention, cascading deactivation, per-tenant codes and a read-only screen. Reporting lines wait for employees. |
| Notifications | Contract expiry, approvals and alerts all need a delivery channel. |
| File storage | Documents, identity attachments, payslips. S3-compatible with signed URLs per `SECURITY_MODEL.md`. |
| ~~Architecture guardrails in CI~~ | **Done.** `scripts/check_architecture.py` enforces three rules and runs in CI. It caught two real violations the day it was written. |

**Phase 1 exit criteria** (from `ROADMAP.md`): two isolated tenants, no cross-tenant access,
bilingual UI, security tests passing. **All four now hold** — isolation is enforced by the
database as well as by application code.

---

## 4. Then — Phase 2 and TIX depth

Phase 2 (employees, contracts, documents, history, expiry alerts) is now unblocked: an employee
needs a person to reference and a unit to belong to, and both exist.

TIX depth can then be built on a real foundation:

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
