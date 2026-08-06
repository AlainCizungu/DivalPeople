# ADR 0002 — Shared-schema multi-tenancy with row-level tenant scoping

- **Status:** Accepted
- **Date:** 2026-08-05

## Context
DIP is multi-tenant SaaS serving banks, NGOs, telecoms, government, and healthcare organisations of roughly 100–5,000 workers. Regulated customers may later require stronger isolation. We must pick an isolation model now because it shapes every query, cache key, file path, and index in the system.

The options considered were database-per-tenant, schema-per-tenant, and shared schema with a `tenant_id` discriminator.

## Decision
Use a **shared database and shared schema**, with `tenant_id` on every tenant-owned row.

Enforcement is layered, because a discriminator column alone is one forgotten `WHERE` clause away from a cross-tenant leak:

1. **Tenant context** is resolved from the authenticated principal only — never from a header, query parameter, or body field. Persisting a tenant-owned entity outside a tenant context throws rather than writing an orphan row.
2. **Explicit tenant predicates.** Every repository finder for a tenant-owned entity takes `tenantId`. There is exactly one deliberate cross-tenant query, `DebtRecordRepository.findAcrossOperators`, reachable only from `ExchangeService`.
3. **PostgreSQL row-level security** provides defence in depth, so a mistake in application code does not become a data breach.
4. **Cross-tenant tests are mandatory** for every module and run in CI.

### Current state of enforcement

Layers 1, 2 and 4 are implemented. Layer 3 is **defined but not yet binding**: the policies exist on
tenant-owned tables and key off `current_setting('app.tenant_id')`, but they only take effect once
the application connects as the non-owner `dip_app` role and sets that GUC per connection
checkout. Local development currently connects as the schema owner, which bypasses RLS.

Until that wiring lands, application-level scoping and the isolation tests are the *primary*
control rather than a backstop. This is the single most valuable follow-up in the tenancy area
and should not be left open once real customer data exists.

The only permitted cross-tenant reads are explicit platform-administration services and the TIX exchange services, both of which are separately authorized and fully audited.

Dedicated schema or database per tenant remains available as a deployment option for regulated customers without changing application code, since all access already flows through the tenant context.

## Consequences
**Positive.** One migration set, one connection pool, straightforward operations and backups. Onboarding a tenant is a row, not an environment.

**Negative.** The blast radius of an isolation bug is large, which is precisely why RLS is enabled rather than trusting the ORM. Noisy-neighbour effects must be managed with indexing and, later, read replicas.

**Follow-up.** Every new tenant-owned table must extend the shared base entity and add its RLS policy in the same migration. CI fails the build if a tenant-owned table has no policy.
