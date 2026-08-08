# DIP — API Standards

Base: /api/v1

## Principles
- REST for core transactional APIs
- JSON UTF-8
- OpenAPI documentation
- OAuth2/OIDC
- Tenant context derived from authenticated authorization, never trusted from client alone
- Idempotency keys for imports and writes where appropriate
- Correlation IDs
- Pagination
- Rate limiting
- Versioning
- Structured error format
- Audit all sensitive reads

## Example Domains
/auth
/organizations
/users
/imports
/entities
/businesses
/individuals
/search
/exposures
/risk-assessments
/matches
/disputes
/reports
/audit
/tix
/portfolio
/ai

## Search API
Search requests require:
- query
- entity type
- purpose code
- optional approved filters

Responses return:
- candidates
- confidence
- minimal permitted attributes
- source count
- risk summary where authorized

## Error Contract
{
  "code": "DIP-SEARCH-403",
  "message": "Not authorized for this search purpose",
  "correlation_id": "...",
  "details": {}
}

Do not expose internal stack traces or sensitive data in errors.
