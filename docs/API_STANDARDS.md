# Dival People — API Standards

## Style
RESTful JSON over HTTPS, documented with OpenAPI, versioned under `/api/v1`.

## Naming
- Plural nouns
- Kebab-case URLs
- camelCase JSON
- Actions only when a resource transition is clearer, e.g. `POST /payroll-periods/{id}/approve`

## Authentication
OAuth 2.0/OpenID Connect bearer tokens. Every request has authenticated user, tenant context, request ID, and server-side authorization.

## Headers
- `Authorization`
- `Content-Type: application/json`
- `Accept: application/json`
- `X-Request-ID`
- `Accept-Language`
- `Idempotency-Key` for sensitive operations

## Pagination
`?page=1&pageSize=25`

Response:
```json
{
  "items": [],
  "page": 1,
  "pageSize": 25,
  "totalItems": 0,
  "totalPages": 0
}
```

## Filtering and sorting
Examples:
- `?status=active`
- `?departmentId=...`
- `?sort=lastName,asc`

## Standard errors
```json
{
  "error": {
    "code": "EMPLOYEE_NOT_FOUND",
    "message": "The employee could not be found.",
    "details": [],
    "requestId": "uuid",
    "timestamp": "2026-08-05T18:00:00Z"
  }
}
```

Codes are language-independent; messages may be localized.

## Idempotency
Required for payroll approval, partner applications, partner callbacks, imports, exports, and document generation.

## Webhooks
Include event ID, event type, timestamp, tenant reference, signature, and retry metadata. Support signing, retry, replay protection, dead-letter handling, and delivery logs.

## Versioning
Do not break clients without a major version. Mark deprecations and publish migration guidance.

## Security
Rate limits, size limits, input validation, object-level authorization, tenant isolation, PII minimization, and no sensitive logging.

## OpenAPI requirements
Every endpoint documents summary, permissions, request/response schemas, validation, errors, examples, and idempotency behavior.
