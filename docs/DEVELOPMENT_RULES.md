# Dival People — Development Rules

## Principles
Build bounded features, preserve modules, prefer readable code, test before merge, enforce authorization and tenant isolation, never log sensitive data, never commit secrets, and avoid premature microservices.

## Repository
```text
/apps
  /web
  /api
/services
  /ai
/packages
  /ui
  /shared
/docs
/infra
/scripts
```

## Branches
Use short-lived branches such as `feature/employee-records`, `fix/tenant-isolation`, or `security/payroll-authorization`.

## Pull requests
Include problem, solution, screenshots, tests, security impact, database impact, localization impact, and rollback plan.

## Definition of done
- Requirements met
- Server-side authorization
- Tenant isolation verified
- English/French supported
- Validation and audit events implemented
- Unit/integration/UI tests pass
- Documentation updated
- No unresolved critical security issue

## Backend
Dependency injection, clear service boundaries, DTOs at APIs, input validation, typed errors, structured logs, request IDs, transactions, and migrations. Never expose database entities directly.

## Frontend
TypeScript strict mode, accessible components, translation keys, schema-based forms, central API client/error handling, complete loading/empty/error states, responsive layouts, and permission-aware navigation.

## Logging
Allowed: request, tenant, user, resource, operation, result, duration.
Forbidden: passwords, tokens, IDs, bank accounts, health data, document content, detailed payroll values.

## Database
Every tenant table has `tenant_id`; every query is scoped. Use keys, indexes, migrations, and preserve financial history.

## AI coding-agent rules
Agents must read relevant docs, propose a plan before large work, keep scope bounded, avoid unrelated refactors, add/run tests, state assumptions and risks, never weaken controls, and never claim tests passed unless executed.

## Prohibited shortcuts
Hard-coded tenant IDs, disabled authorization, plaintext sensitive data, hard-coded translations, direct production edits, unreviewed migrations, automatic high-impact AI decisions, silent errors, or shared credentials.
