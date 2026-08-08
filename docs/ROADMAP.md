# DIP — Execution Roadmap

## Strategy
Build the smallest trustworthy intelligence exchange first, using real telecom data, then expand the network.

## Where the code actually is, 8 August 2026

The phases below are the plan. This table is the present, and it is deliberately first: the plan
reads as though it starts from nothing, and it does not. Some of Phase 1 is done, none of Phase 2
is, and TIX exists in a form that predates this design.

| Phase | Capability | State |
|---|---|---|
| 1 | Tenants, OIDC sign-in, RBAC, EN/FR, audit, PostgreSQL, files, notifications, CI/CD | **Built**, tenant isolation proved by tests |
| 1 | MFA | Not built — Keycloak supports it, it is not turned on |
| 1 | Object storage | Filesystem locally; no S3-compatible store |
| 1 | Observability | Structured logs with request ids; no metrics, traces or alerting |
| 2 | CSV and XLSX upload, import batches, raw immutable storage, provenance | **Built.** Header found past a preamble; multi-sheet workbooks and dated cells are refused or left raw |
| 2 | Source mappings, validation engine, normalisation, aging/balance mapping | **Not built** — waiting on the Vodacom sample, deliberately |
| 2 | Import preview, rejection report, data-quality dashboard | Preview built (rows as stored); quality reporting not |
| 3 | Deterministic entity resolution | **Built** for the write path (`SubjectResolver`), exact match only, refuses ambiguity |
| 3 | Fuzzy matching with confidence | **Built** for the read path (`IdentityMatcher`) |
| 3 | Merge / unmerge, human review queue | Not built |
| 3 | Universal search, business and individual profiles | Not built |
| 4 | Cross-operator inquiry returning status, never another operator's data | **Built**, rate-limited, audited with a stated purpose |
| 4 | Declaration by an operator, with a reporting threshold | **Built** |
| 4 | Retention, expiry and real erasure | **Built** — the periods are unverified placeholders, see `TIX_RETENTION.md` |
| 4 | Aging buckets and an operator exposure view | **Built.** Aged from the default date, per currency, own records only |
| 4 | Write-off indicator, authorised reports, portfolio alerts | Not built — write-off is decision 2 in `TIX_SOURCE_PROFILE.md` |
| 5 | Risk rating with reason codes and a model version | **Not built.** The score on the landing page is invented |
| 6+ | Pilot, multi-operator production, banking, AI | Not started |

### The gap that mattered most, and what is left of it

**TIX had no provenance until V20.** `tix_debt_record` was the origin of truth: an operator
declared through the API and nothing recorded which file, batch or row a figure came from. Rules 4
and 5 in `AGENTS.md` were aspirations against the schema rather than descriptions of it.

Closed, in part. There is now `data_source` → `import_batch` → `raw_record`, rows are immutable by
database rule and erasable by retention, and every debt record carries an `origin` that a check
constraint ties to a source row — an `IMPORT` must name one, an `API_DECLARATION` must not pretend
to have one. A file can be uploaded, inspected exactly as stored, published or withdrawn.

**What remains is the mapping.** Nothing is derived from a batch yet: publishing a delivery
creates no exposures, so a raw row and a debt record are still two unconnected worlds. Closing
that means deciding which column is a balance, which is an identifier, and what an aging bucket
looks like — and the roadmap is explicit that those come from a real export rather than a guess.
Until it is built, `origin` is `API_DECLARATION` on every row in the system.

The aging vocabulary is now the one thing that crosses the gap in advance. `AgingBand` takes its
edges from the columns of the profiled Vodacom export rather than from a generic 30/60/90, so a
declared record and an imported one will land in the same bands when the mapping arrives. The
exposure screen states the gap out loud — it shows how many of its records came from a file, and
that figure is zero.

Anyone picking this up should read `TIX_MODULE.md` for what exists before designing what replaces
it.

### Superseded

An HR product (Dival People) was built through payroll and self-service and is being withdrawn
from this repository. The screens are gone; the backend modules and migrations V5 and V8–V17 are
still present and still green, and come out in their own change. `PAYROLL_SCOPE.md` belongs to it.

---

## Phase 0 — Legal, Data & Product Discovery (Weeks 1–4)
Deliverables:
- stakeholder map
- data inventory
- source ownership/permission review
- sample-data profiling
- DRC legal/regulatory counsel review
- data-sharing principles
- product scope
- threat model
- architecture decision record
- pilot success metrics

Exit criteria:
Dival AI and partners know exactly what data may be processed, for what purpose, and who can access it.

## Phase 1 — Platform Foundation (Weeks 3–8)
Build:
- repository/CI/CD
- environments
- authentication + MFA
- tenants/organizations
- RBAC
- bilingual framework
- audit foundation
- PostgreSQL schema
- object storage
- observability
- base Microsoft-inspired UI shell

Exit:
Users can securely sign in, switch EN/FR, and tenant/audit controls are operational.

## Phase 2 — Telecom Data Foundation (Weeks 6–12)
Build:
- XLSX/CSV upload
- source mappings
- import batches
- validation engine
- raw immutable storage
- normalization
- aging/balance mapping
- import preview
- rejection report
- data-quality dashboard

Use the existing telecom spreadsheets to define real mappings instead of inventing a generic schema.

Exit:
A telecom spreadsheet can be imported repeatedly, validated, traced, and published safely.

## Phase 3 — Entity Resolution & Universal Search (Weeks 10–16)
Build:
- canonical business entity
- canonical individual entity
- identifier registry
- deterministic matching
- fuzzy matching
- match confidence
- human review queue
- merge/unmerge
- universal search
- business profile
- individual profile

Exit:
Analysts can reliably find and consolidate records without losing source provenance.

## Phase 4 — TIX MVP (Weeks 14–20)
Build:
- telecom exposure view
- aging
- source/operator view
- active/inactive status
- write-off/recovery indicators
- cross-operator matches
- repeat-default indicators
- telecom portfolio dashboard
- authorized reports
- search-purpose capture

Exit:
TIX is demoable with real, governed telecom data and useful to collections/credit teams.

## Phase 5 — Risk Intelligence v1 (Weeks 18–24)
Build:
- rule-based DIP risk rating
- reason codes
- identity confidence
- data freshness
- source count
- risk history
- configurable thresholds
- model/version registry
- analyst review

Do not start with opaque ML. Establish a transparent baseline first.

Exit:
Every risk output is explainable and reproducible.

## Phase 6 — Controlled Pilot (Weeks 22–30)
Pilot with selected institutional users.
Activities:
- UAT
- data-quality remediation
- security test
- performance test
- user training
- support process
- incident response
- feedback
- pricing/usage measurement
- governance review

Exit:
Pilot KPIs are met and critical issues resolved.

## Phase 7 — Multi-Telecom Production (Months 8–12)
- onboard additional telecom sources
- automated/scheduled feeds
- API integration
- portfolio alerts
- dispute/correction workflow
- SLA/support model
- production DR
- independent penetration test
- formal operating governance

## Phase 8 — Banking & Cross-Industry Expansion (Year 2)
Only after telecom foundation is trusted:
- bank onboarding
- business credit intelligence
- individual risk workflows where legally permitted
- financial exposure APIs
- fraud signals
- monitoring
- additional institutional data

## Phase 9 — AI & Predictive Intelligence (Year 2+)
- grounded AI analyst
- recovery probability
- collection prioritization
- anomaly models
- network relationship analysis
- explainable predictive risk
- executive AI summaries

## First 30 Days
Week 1:
- secure and inventory datasets
- document every field
- profile missingness/duplicates
- identify source and refresh process
- establish data-handling rules

Week 2:
- canonical data model
- first telecom mapping
- architecture skeleton
- repo + environments
- auth/tenant design

Week 3:
- import pipeline
- validation
- raw storage
- bilingual application shell
- audit logging

Week 4:
- normalized telecom records
- first search endpoint
- business profile prototype
- data-quality dashboard
- demo using anonymized/approved records

## 90-Day Target
A secure bilingual MVP capable of ingesting the existing telecom dataset, validating it, resolving entities, searching businesses, showing source-backed exposure/aging, and producing auditable reports.

## 12-Month Target
A production TIX network with multiple telecom participants, governed cross-source intelligence, APIs, risk intelligence, portfolio monitoring, operational support, and a credible path to bank participation.
