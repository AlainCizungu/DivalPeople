# DIP — Technical Architecture

## Architecture Principles
- Modular monolith first; split services only when scale/ownership requires it.
- API-first.
- Multi-tenant from day one.
- Source provenance is mandatory.
- Raw source data is immutable.
- Canonical entities are separated from source records.
- Risk outputs are versioned and explainable.
- AI cannot bypass authorization.
- English/French localization is platform-level.

## Recommended Initial Stack
Frontend: Next.js/React + TypeScript
Backend: Python FastAPI or Java Spring Boot (choose one primary backend for MVP)
Database: PostgreSQL
Cache: Redis
Object storage: S3-compatible storage for imports/reports
Search: PostgreSQL full-text initially; OpenSearch/Elasticsearch when scale requires
Async jobs: queue/worker architecture
Identity: OIDC/OAuth2 provider with MFA
Infrastructure: containerized deployment, IaC, CI/CD
Observability: logs, metrics, traces, security audit events

## Decisions already taken

The stack section above offers choices. These were made and are in the code, so they are not open
questions:

- **Backend: Java 21 + Spring Boot 3.4**, not FastAPI. One primary backend, as the section asks.
- **Migrations: Flyway.** Released migrations are immutable — V1–V19 have been applied, and a
  released migration is never edited, only superseded.
- **Identity: Keycloak** via OIDC. MFA is available and not yet enabled.
- **Tenancy: shared schema with `tenant_id` and PostgreSQL row-level security**, with the
  application connecting as an unprivileged role that cannot bypass the policies. See ADR 0002.
- **The browser never holds a token.** A backend-for-frontend keeps sessions in Redis behind an
  opaque cookie and attaches the bearer token server-side. See ADR 0003.
- **Modular monolith**, module boundaries enforced in CI by `scripts/check_architecture.py`. See
  ADR 0001.
- **Tests: JUnit 5 with Testcontainers** against real PostgreSQL, never an in-memory substitute.

## Logical Architecture
Users
→ Web Application / API Clients
→ API Gateway / Backend
→ Authorization & Purpose Checks
→ Domain Modules
   - Organizations & Users
   - Data Ingestion
   - Entity Resolution
   - Search
   - Risk Intelligence
   - TIX
   - Portfolio Monitoring
   - Reporting
   - Audit
   - AI Assistant
→ Data Layer
   - PostgreSQL canonical store
   - Immutable import/object store
   - Cache
   - Search index
→ Integration Layer
   - File ingestion
   - APIs
   - Future bank/telecom connectors

## Environments
- Local
- Development
- Staging/UAT
- Production

Production data must never be copied to lower environments without approved anonymization.

## Deployment Evolution
Phase 1: single-region secure production.
Phase 2: high availability and warm standby.
Phase 3: multi-region / jurisdiction-specific deployments where required.
