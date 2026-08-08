# DIP — Database Design

## Core Rule
Never model the platform as one giant debtor table.

Maintain:
1. Source organization
2. Import batch
3. Immutable source record
4. Canonical entity
5. Entity-source link
6. Account/exposure
7. Payment/recovery event
8. Risk assessment
9. Match candidate
10. Audit event

## What is implemented today, and how it differs

This design is the target. The schema in the repository is not it yet, and the difference is
structural rather than cosmetic — anybody reading this document as a description of the database
will be wrong about the most important part.

Implemented (migrations V1–V19):

| This design calls for | The database has |
|---|---|
| `tenants` | `tenant` — participating institution |
| `users` | `user_account`, provisioned from the OIDC token on first request |
| `entities` (BUSINESS / INDIVIDUAL) | `tix_subject`, with a `subject_type` of INDIVIDUAL or BUSINESS |
| `entity_identifiers` | `tix_subject_identifier`, typed and globally unique per (type, value) |
| `exposures` | `tix_debt_record` — amount, currency, status, default date, retention date |
| `audit_events` | `audit_event`, append-only by database rule as well as by privilege |
| `data_sources`, `import_batches`, `raw_records` | **nothing** |
| `entity_source_links` | **nothing** |
| `business_profiles`, `individual_profiles` | **nothing** — one flat subject row |
| `accounts`, `aging_snapshots`, `payments`, `recoveries` | **nothing** |
| `disputes` | a status on the debt record, settable only by the operator |
| `match_candidates` | **nothing** — ambiguity is refused at write time, never queued |
| `risk_assessments`, `risk_signals` | **nothing** |
| `searches` | inquiries land in `audit_event` with a stated purpose, not a table of their own |
| `reports` | **nothing** |

### The consequence

`tix_debt_record` is currently the origin of truth. There is no import batch, no raw record, and
no source link, so **the lineage this document requires cannot be produced** — a displayed figure
traces back to an API call and stops there. Rules 4 and 5 in `AGENTS.md` are aspirations against
the present schema, not descriptions of it.

Closing that gap means raw records become the origin and debt records become derived. It is the
substance of Phase 2, and it is a migration of the core model rather than an addition to it. Do
not add columns to `tix_debt_record` in the meantime expecting them to survive.

## Core Entities
### tenants
Institution participating in DIP.

### users
Authorized user associated with tenant(s), role, language, status.

### data_sources
Defines operator/bank/source system and dataset type.

### import_batches
File/API ingestion metadata: checksum, uploader, source, schema version, row counts, validation state.

### raw_records
Immutable normalized representation of each imported row plus original payload reference.

### entities
Canonical subject:
- BUSINESS
- INDIVIDUAL

### business_profiles
Legal name, aliases, registration/tax identifiers where available, sector, address metadata.

### individual_profiles
Personal attributes permitted by policy. Sensitive fields encrypted/masked.

### entity_identifiers
Typed identifiers with verification/source/confidence metadata.

### entity_source_links
Maps canonical entity to source records and matching confidence.

### accounts
Institution-specific customer/account relationship.

### exposures
Balance, currency, status, dates, aging, write-off state.

### aging_snapshots
Historical aging values by observation date.

### payments / recoveries
Optional payment and recovery events when supplied.

### disputes
Correction/dispute workflow.

### match_candidates
Potential duplicate/cross-source entity matches requiring review.

### risk_assessments
Score/rating, model version, factors, reason codes, generated time.

### risk_signals
Individual explainable signals.

### searches
Purpose, user, tenant, search terms hashed/redacted where appropriate, result count.

### reports
Generated intelligence reports and authorization metadata.

### audit_events
Append-only security/business audit trail.

## Data Lineage
Every displayed financial fact must be traceable:
displayed value → canonical record → source record → import batch → source organization.

## Retention
Retention must be configurable by data category, source agreement, applicable law, and dispute status.
