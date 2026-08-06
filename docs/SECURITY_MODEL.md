# Dival People — Security Model

## Objectives
Protect employee PII, payroll, bank information, identity documents, health/insurance data, fraud investigations, credentials, and tenant data.

## Principles
Zero trust, least privilege, defense in depth, secure defaults, privacy by design, complete auditability, strict tenant isolation, encryption, and human review for high-impact decisions.

## Authentication
- OpenID Connect/OAuth 2.0
- MFA for privileged users
- Enterprise SSO option
- Session expiration
- Login monitoring and lockout controls
- Audited password reset

## Authorization
RBAC with optional attributes. Roles may include Platform Admin, Tenant Admin, HR Admin, HR Manager, Payroll Officer, Finance Officer, Compliance Officer, Recruiter, Manager, Employee, and Auditor.

Authorization is enforced server-side. Hiding a frontend control is not authorization.

## Tenant isolation
- Authenticated context establishes tenant.
- All queries, cache keys, files, jobs, and search indexes are tenant-scoped.
- Cross-tenant tests are mandatory.
- Platform administration is isolated and audited.

## Encryption
TLS 1.2+ in transit. Managed encryption for databases, files, backups, and sensitive fields. Keys are stored in a managed key service and rotated.

## Secrets
No secrets in code or Git. Use environment-specific managed secret storage and audit access.

## Audit
Append-only logs include tenant, actor, action, resource, request ID, timestamp, IP, outcome, and before/after values when appropriate.

## Financial-service controls
Explicit consent, partner authentication, signed messages, idempotency, reconciliation, data minimization, disclosures, and partner-status verification.

## Fraud safeguards
Alerts are indicators, not findings. Show evidence and confidence, require human review, support false-positive resolution, restrict access, and prevent automatic discipline or termination.

## AI safeguards
- Permission-aware retrieval
- Minimal sensitive data in prompts
- Prompt-injection defenses
- Uploaded files treated as untrusted
- Advisory output labels
- Audit model access and output
- Approved AI providers only
- No autonomous employment, fraud, credit, insurance, or payroll decisions

## Application controls
Input validation, output encoding, CSRF controls where relevant, secure cookies, CSP, rate limiting, upload scanning, SQLi/XSS/SSRF prevention, dependency scanning, SAST, DAST, and container scanning.

## File controls
Allowlist types, size limits, malware scan, randomized keys, checksums, signed temporary URLs, and access auditing.

## Privacy
Consent, access, correction, export, retention, deletion where lawful, purpose limitation, and data minimization. Country-specific legal review is required before production.

## Production readiness
Threat model, penetration test, authorization test, tenant-isolation test, API security test, upload security test, backup restore test, and incident-response exercise.
