# DIP — Testing Strategy

## Test Layers
### Unit
Normalization, validators, aging calculations, risk factors, permissions.

### Integration
Database, import pipeline, object storage, search, auth, report generation.

### Contract/API
OpenAPI compatibility, schema validation, error contracts.

### End-to-End
- bilingual login
- upload dataset
- validate
- publish
- search business
- search individual
- review match
- generate report
- audit search
- dispute/correction

### Security
- tenant isolation
- IDOR/BOLA
- privilege escalation
- export authorization
- injection
- file upload abuse
- session security
- API rate limits
- secrets scanning
- dependency scanning

### Data Quality
Use representative synthetic/anonymized datasets with:
- duplicate names
- missing IDs
- inconsistent casing/accents
- negative/invalid balances
- malformed dates
- multiple currencies
- duplicate source IDs
- conflicting records

### Entity Resolution
Maintain labeled test pairs:
- true match
- true non-match
- ambiguous
Measure precision/recall and review thresholds.

### Risk Model
Before production:
- back-testing
- calibration
- explainability
- stability
- fairness review where applicable
- drift baseline

### Performance
MVP targets should be established from pilot volumes. Test bulk import, search latency, concurrent analysts, report generation, and audit throughput.
