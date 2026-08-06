# Dival Intelligence Platform — Delivery Plan

Living companion to [`ROADMAP.md`](ROADMAP.md). The roadmap says what to build in what order; this
records where we actually are, what is prioritized next, and which gaps are knowingly open.

Update it when a phase changes status or a gap is closed. Last reviewed: August 2026.

---

## 1. Current status

| Phase | Status | Notes |
|---|---|---|
| 0 — Foundation | **Largely complete** | Docs, repo, local environment, CI, design system, security baseline, ADRs 0001–0002. Missing: staging/production environments, CD pipeline. |
| 1 — Platform | **~40%** | Authentication, role mapping, audit write path, EN/FR, tenant entity and isolation tests are in. Users, organization structure, notifications and file storage are not. |
| 2 — Core HR | Not started | Blocked on Phase 1 users and organization structure. |
| 3 — Recruitment & onboarding | Not started | |
| 4 — Time, performance, learning | Not started | |
| 5 — Payroll preparation | Not started | |
| 6 — Employee self-service | Not started | |
| 7 — Financial services | Not started | |
| 8 — Fraud intelligence | Not started | |
| 9 — Industry editions | **TIX partially built, out of order** | The telecom exchange was built ahead of its phase for commercial reasons. Subjects, identifiers, debt records, deterministic matching, inquiry API and UI exist. |
| 10 — Advanced AI | Not started | |

### What exists today

- **Backend** — 5 tables (`tenant`, `audit_event`, `tix_subject`, `tix_subject_identifier`,
  `tix_debt_record`), 2 modules (`tenants`, `tix`), 4 endpoints, 14 passing tests including four
  cross-tenant isolation tests against real PostgreSQL.
- **Frontend** — public landing page, authenticated product shell, TIX verification screen,
  bilingual throughout with parity enforced in CI.
- **Local environment** — one command brings up PostgreSQL, Redis and Keycloak with a realm,
  three fixture users across two tenants, and a scripted end-to-end check.

### Building TIX first — the trade-off we took

TIX belongs to Phase 9 but was built early to support the telecom opportunity. That was a
deliberate choice, and it leaves a hollow middle: the edition exists without the platform beneath
it. Section 2 is largely about refilling that middle before the gap compounds.

---

## 2. Priority now — tenancy and identity foundation

These three are treated as **one piece of work** because they touch the same layer. Doing them
separately means touching tenancy three times.

### P0.1 — Local user records and tenant membership

Identity currently lives entirely in Keycloak. There is no `users` table, which means:

- `audit_event.actor_id` holds a Keycloak subject UUID that references nothing, so "who ran this
  verification" cannot be answered inside the system
- tenant membership is a Keycloak user attribute, so members cannot be listed or managed
- nothing can reference a person — Phase 2 employees, approvals and assignments all need to

**Deliverables:** `users` module with a `user_account` table keyed by the OIDC subject; tenant
membership; provisioning on first authenticated request; audit joined to a real person; admin
endpoints to list members.

### P0.2 — Tenant provisioning

Tenants are seeded by hand with fixed UUIDs in a local-only bean. There is no supported way to
create one.

**Deliverables:** platform-admin endpoint to create and deactivate a tenant; the local seeder
becomes a caller of the same service rather than a parallel path.

### P0.3 — Make row-level security bind

Documented as open in [ADR 0002](adr/0002-shared-schema-multitenancy.md). Policies exist on
tenant-owned tables but do not apply, because the application connects as the schema owner.
Application-level scoping is currently the only control.

This is inert risk today — there is no customer data — and it is deliberately scheduled here
rather than earlier for that reason. It should not survive contact with real data.

**Deliverables:** connect as the non-owner `dip_app` role; set `app.tenant_id` per connection
checkout; a test proving raw SQL cannot cross tenants; ADR 0002 updated.

---

## 3. Next — completing Phase 1

| Item | Why it matters |
|---|---|
| Organization structure | Legal entities, branches, departments, cost centers, reporting lines. Phase 2 cannot start without it. |
| Notifications | Contract expiry, approvals and alerts all need a delivery channel. |
| File storage | Documents, identity attachments, payslips. S3-compatible with signed URLs per `SECURITY_MODEL.md`. |
| Architecture guardrails in CI | `AGENTS.md` states rules that nothing enforces: module boundaries, an RLS policy for every tenant-owned table, no cross-module repository access. Rules that are not checked decay. |

**Phase 1 exit criteria** (from `ROADMAP.md`): two isolated tenants, no cross-tenant access,
bilingual UI, security tests passing. The first three hold today; the fourth is partial until
P0.3 lands.

---

## 4. Then — Phase 2 and TIX depth

Phase 2 (employees, contracts, documents, history, expiry alerts) becomes unblocked once
organization structure exists.

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
| RLS defined but not binding | High once real data exists | ADR 0002, P0.3 |
| No local user records | High — blocks most modules | P0.1 |
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
