# AGENTS.md

AI coding agents must read all files in `/docs` before making architectural or feature changes.
Start with [`docs/DELIVERY_PLAN.md`](docs/DELIVERY_PLAN.md) for current status, what is prioritized
next, and which gaps are knowingly open.

## Mandatory rules
- Preserve tenant isolation.
- Enforce authorization server-side.
- Do not log or expose sensitive data.
- Use English and French translation keys.
- Use reviewed database migrations.
- Add and run tests.
- Do not add microservices without an approved ADR.
- AI may not make final employment, fraud, lending, insurance, discipline, or payroll decisions.
- Do not modify unrelated modules.
- Propose a plan before large changes.
- State assumptions, risks, and commands executed.
- Never claim a test passed unless it was actually run.

## Definition of done
Requirements met; authorization implemented; tenant isolation tested; bilingual UI supported; validation/audit added; tests executed; documentation updated.

---

## Repository map

```
backend/    Java 21 + Spring Boot modular monolith
frontend/   Next.js + TypeScript + Tailwind
infra/      Local development infrastructure (Docker Compose)
docs/       Product, architecture, security, design documentation
docs/adr/   Architecture decision records
```

## Where code goes

Backend packages live under `ai.dival.dip`:

| Package | Contents |
|---|---|
| `common.tenancy` | Tenant context, resolution filter, Hibernate filter configuration |
| `common.audit` | Append-only audit log |
| `common.security` | Authentication, RBAC, method-level authorization |
| `common.web` | Error handling, API envelope |
| `modules.tenants` | Customer organizations |
| `modules.users` | Local user records, provisioned just-in-time from the OIDC subject |
| `modules.tix` | Telecom Information Exchange |
| `modules.<name>` | One package per further business module — `employees`, `payroll`, … |

A module owns its entities and repositories. **Cross-module access goes through the other module's service interface**, never through its repositories or tables directly.

## Commands

```bash
cd backend  && ./gradlew test            # backend tests, includes tenant isolation
cd backend  && ./gradlew bootRun         # run API on :8080
cd frontend && npm run typecheck         # TypeScript
cd frontend && npm run build             # production build
python3 scripts/check_architecture.py    # module boundaries and RLS policies
./infra/dev.sh up && ./infra/dev.sh check
```

## What is actually enforced

Three of the rules below are checked by `scripts/check_architecture.py`, which runs in CI:

1. `common/` never imports from `modules/` — shared code is the foundation, not a peer.
2. No module imports another module's repository — go through its service.
3. Every tenant-owned table has a row-level security policy with both `USING` and `WITH CHECK`.

Composition code that must know about modules — the local seeders, for instance — lives in
`dev/`, a sibling of `common/` and `modules/`, not inside `common/`.

## Non-negotiables for every change

**Tenancy.** Every tenant-owned entity extends `TenantOwnedEntity`. Every new tenant-owned table adds its row-level security policy in the same migration. Tenant context is read from the authenticated principal — never from a header, parameter, or body field.

**Migrations.** Flyway, forward-only, one concern per file, named `V<n>__<snake_case>.sql`. Never edit an applied migration; add a new one.

**Localization.** No user-facing string is hard-coded. Every key is added to both `frontend/messages/en.json` and `fr.json` in the same change.

**Tests.** A module is not done until it has a cross-tenant isolation test proving tenant A cannot read tenant B's rows.

**AI features.** Output is advisory and labelled as such. Alerts are indicators requiring review, not findings of misconduct.
