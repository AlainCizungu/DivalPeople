# ADR 0001 — Modular monolith as the initial architecture

- **Status:** Accepted
- **Date:** 2026-08-05

## Context
DIP spans several large domains — core HR, payroll, financial services, fraud intelligence, and the TIX exchange — and must serve multiple tenants across countries. The team is small and the product is pre-first-customer. Microservices would impose distributed transactions, network failure modes, and independent deployment pipelines before we have the traffic, the team size, or the regulatory isolation requirements that justify them.

## Decision
Build a **modular monolith**: one deployable Spring Boot application, internally divided into modules with explicit boundaries (`modules/<name>`), each owning its persistence and exposing a service interface to other modules.

A module may be extracted into a separate service only when a documented driver applies:
1. Independent scaling of a genuinely hot path
2. Regulatory isolation of a specific data class
3. A reliability boundary that must not share a failure domain
4. Distinct team ownership with an independent release cadence

Extraction requires a new ADR.

## Consequences
**Positive.** One build, one deploy, one database transaction boundary. Refactoring across module lines stays cheap while the domain is still moving. Local development needs only Postgres and Redis.

**Negative.** Module boundaries are convention-enforced, so discipline is required — cross-module access must go through service interfaces, never through another module's repositories or tables. CI checks package dependencies to keep this honest.

**Follow-up.** Introduce a transactional outbox before any event-streaming infrastructure, per `ARCHITECTURE.md`.
