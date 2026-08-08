# DIP — Development Rules

1. Never bypass tenant authorization.
2. Never expose a source record without permission checks.
3. Every sensitive read is auditable.
4. Raw imports are immutable.
5. Canonical entity merges are reversible.
6. No automatic ambiguous merge.
7. No risk score without version + reasons.
8. AI output cannot be treated as source truth.
9. No hard-coded English/French strings.
10. No production secrets in code.
11. No production PII in tests.
12. Database migrations are reviewed and reversible where practical.
13. APIs require schema validation.
14. Security tests are part of CI.
15. Every feature requires acceptance criteria.
16. Critical workflows require integration tests.
17. Imports must be idempotent or safely retryable.
18. Every source field mapping is versioned.
19. Avoid premature microservices.
20. Prefer explainability and auditability over cleverness.

## Git
- main protected
- feature branches
- pull requests
- automated lint/test/security checks
- conventional commit style recommended

## Definition of Done
Code + tests + authorization + audit + localization + observability + documentation + acceptance criteria.
