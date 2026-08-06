# Dival People — Architecture

## Goals
Secure multi-tenant SaaS, English/French support, auditability, modular delivery, financial-partner integrations, AI assistance, fraud analytics, and optional dedicated/private deployment.

## Initial architecture
Use a **modular monolith**. Introduce microservices only when independent scaling, regulatory isolation, reliability boundaries, or team ownership clearly require them.

## Recommended stack
- Frontend: Next.js, React, TypeScript, Tailwind CSS
- Backend: Java 21+, Spring Boot, Spring Security, Spring Data JPA
- AI services: Python/FastAPI where appropriate
- Database: PostgreSQL
- Cache/jobs: Redis
- Files: S3-compatible object storage
- Identity: Keycloak, Auth0, Cognito, or Entra External ID
- Migrations: Flyway
- Observability: OpenTelemetry plus Datadog/Grafana
- Deployment: Docker; managed containers first; Kubernetes later when justified

## Core modules
`authentication`, `authorization`, `tenants`, `organizations`, `users`, `employees`, `contracts`, `documents`, `recruitment`, `onboarding`, `leave`, `attendance`, `performance`, `learning`, `payroll`, `benefits`, `financial_services`, `fraud_intelligence`, `notifications`, `integrations`, `audit`, `analytics`, `localization`, `ai_assistant`.

## Multi-tenancy
Initial model: shared database/shared schema with `tenant_id` on every tenant-owned row.

Rules:
- Tenant context comes from authenticated identity, never trusted request input.
- Every query, cache key, job, file path, search index, and audit event is tenant-scoped.
- Cross-tenant access is prohibited except through explicit platform-administration services.
- Dedicated schema/database/environment may be offered to regulated customers.

## Integration model
Use adapters for banks, insurers, payroll, accounting, identity verification, email, SMS, mobile money, and government systems. Provider-specific logic must not enter core domain modules.

## API model
REST/JSON, OpenAPI, versioned endpoints, webhooks, idempotency for financial operations, background jobs for long-running work.

## Domain events
Examples: `EmployeeCreated`, `ContractExpiring`, `LeaveApproved`, `PayrollApproved`, `FinancialServiceApplicationSubmitted`, `FraudAlertCreated`, `TrainingOverdue`.

Use a transactional outbox before introducing Kafka.

## AI architecture
AI is separated from authoritative rules. It may summarize, extract, match, explain, and recommend, but may not independently approve loans, reject candidates, establish fraud, modify payroll, or trigger discipline.

## Deployment environments
Local, development, test, staging, production. Optional dedicated or private deployments.

## Reliability
Target 99.9% monthly uptime initially, encrypted backups, tested restores, health checks, centralized monitoring, incident response, and defined RPO/RTO.

## Scaling order
1. Optimize SQL and indexes
2. Cache
3. Background jobs
4. Horizontal application scaling
5. Read replicas
6. Split high-load modules
7. Event streaming when justified

Record major decisions in `/docs/adr/`.
