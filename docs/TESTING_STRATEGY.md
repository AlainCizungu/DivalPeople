# Dival People — Testing Strategy

## Objectives
Protect correctness, tenant isolation, security, payroll accuracy, partner integrity, localization, accessibility, and reliability.

## Test layers
### Unit
Domain rules, calculations, validation, permissions, formatting, and fraud rules.

### Integration
Repositories, APIs, authentication, authorization, migrations, jobs, and adapters.

### End-to-end
Employee creation, recruitment-to-onboarding, leave approval, payroll preparation/approval, payslip access, financial-service application, fraud review, and English/French switching.

### Contract
Bank, insurer, payroll, identity, and webhook integrations.

## Critical security tests
Cross-tenant read/write, privilege escalation, object authorization, file access, rate limiting, injection, XSS, CSRF, token expiration, and webhook signatures.

## Payroll tests
Salary, allowance, deduction, currency, rounding, overtime, approvals, finalization, exports, and immutable history.

## Financial-service tests
Consent, eligibility, idempotency, timeouts, duplicate submission, reconciliation, status sync, and audit.

## Fraud tests
Alert generation, confidence, evidence, false positives, human review, access restrictions, and audit trail.

## Localization
English/French, missing keys, text overflow, dates, numbers, currencies, accents, emails, and documents.

## Accessibility
Keyboard, focus, labels, contrast, screen reader, errors, and dialogs.

## Performance targets
- Common API p95 under 500 ms
- Typical dashboard under 3 seconds
- Representative bulk imports
- Payroll batch tests
- Concurrent self-service usage
- Report generation

## Reliability
Backup restore, queue retry, partner outage, database restart, partial deploy, migration recovery, and storage failure.

## Test data
Synthetic only in lower environments, multiple tenants, countries, currencies, and languages.

## CI gates
Formatting, lint, type check, unit, integration, dependency scan, secret scan, and migration validation on PRs. Main branch adds E2E, container/security scans, and build artifacts.

## Release
Smoke, regression, security review, tenant-isolation test, backup verification, rollback validation, and approval.
