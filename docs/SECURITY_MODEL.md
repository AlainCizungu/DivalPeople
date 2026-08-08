# DIP — Security, Privacy & Trust Model

## Security Objective
DIP may hold commercially sensitive and personal financial-risk data. Security and governance are product features, not infrastructure afterthoughts.

## Identity & Access
- MFA for privileged and institutional users
- OIDC/OAuth2
- RBAC plus attribute/purpose-based controls
- Least privilege
- Tenant isolation
- Session controls
- Admin action re-authentication
- Periodic access reviews

## Sensitive Data
- TLS everywhere
- Encryption at rest
- Application/field-level encryption for sensitive identifiers
- Mask identifiers in UI by default
- Secrets manager; no secrets in source control
- Key rotation
- Export controls and watermarking where appropriate

## Purpose-Based Access
A user should not search a person merely because the user has an account.
Search workflow should capture an authorized business purpose such as:
- onboarding
- credit review
- collections
- fraud investigation
- compliance review
- portfolio monitoring

## Audit
Log:
- login/authentication events
- searches
- profile views
- exports
- report generation
- data changes
- entity merges
- risk-score generation
- admin changes
- API access
- permission changes

Audit logs must be tamper-resistant.

## Data Governance
- Participating institutions retain ownership of contributed operational data.
- DIP maintains processing, provenance, access, and derived-intelligence rules.
- Cross-institution sharing is policy-controlled.
- Data minimization applies to every view and API.
- Correction/dispute mechanisms must exist.
- Source agreements define permitted use.

## AI/Model Governance
- Human-in-the-loop
- No autonomous denial/approval in MVP
- Model registry
- Versioning
- Reason codes
- Evaluation before release
- Drift monitoring
- Bias/fairness review where relevant
- Rollback
- Prompt/data leakage controls
- AI output logging appropriate to privacy requirements

## Security Program Roadmap
MVP: threat model, secure SDLC, MFA, encryption, audit, backups.
Pilot: penetration test, incident response drill, privacy review.
Scale: independent security audit, formal control framework, vendor risk, model governance committee, regulator-ready evidence.
