# DIP Backend

Java 21 + Spring Boot modular monolith.

## First-time setup

The Gradle wrapper JAR is not committed, so run the bootstrap script once:

```bash
cd backend
./bootstrap.sh
```

It checks for Java 21, generates `./gradlew` — downloading Gradle temporarily if you don't have it
installed — and tells you whether Docker is available for the integration tests. Safe to re-run.

After that, always use `./gradlew`. The only prerequisite is a JDK 21:

```bash
brew install --cask temurin@21     # macOS
sdk install java 21-tem            # SDKMAN, any OS
```

## Commands

```bash
./gradlew build          # compile + test
./gradlew test           # tests only
./gradlew bootRun        # run on :8080
```

Integration tests use Testcontainers and require Docker to be running.

## Layout

```
ai.dival.dip
├── common
│   ├── tenancy     TenantContext, TenantOwnedEntity, TenantResolutionFilter
│   ├── security    SecurityConfig, Roles
│   ├── audit       AuditEvent, AuditService
│   └── web         GlobalExceptionHandler
└── modules
    ├── tenants     Tenant, TenantRepository
    └── tix         Telecom Information Exchange
```

## Rules that matter here

**Tenant context comes from the token.** `TenantResolutionFilter` reads the `tenant_id` claim of the
validated JWT. Nothing accepts a tenant from a header, parameter, or body.

**Tenant-owned entities extend `TenantOwnedEntity`.** The tenant is stamped on persist from
`TenantContext`, and persisting outside a tenant context throws rather than writing an orphan row.

**Repositories take the tenant explicitly.** Every finder for a tenant-owned entity has a
`tenantId` parameter. `DebtRecordRepository.findAcrossOperators` is the single deliberate
exception and is callable only from `ExchangeService`.

**`ExchangeService` is the only cross-tenant reader.** It authorizes, audits, and returns
normalised indicators — never another operator's rows. Adding a method there is a
security-relevant change.

**Migrations are forward-only.** Flyway owns the schema; Hibernate is set to `validate` and never
alters it. Every new tenant-owned table adds its RLS policy in the same migration.

## Row-level security

Policies are defined on tenant-owned tables and bind to the `app.tenant_id` setting. They take
effect when the application connects as the non-owner `dip_app` role; running as the schema owner
(the default in local development) bypasses them, which is why application-level tenant scoping
and the isolation tests are the primary control rather than a backstop.

Wiring the connection to `SET app.tenant_id` per checkout is the next step — see
`docs/adr/0002-shared-schema-multitenancy.md`.
