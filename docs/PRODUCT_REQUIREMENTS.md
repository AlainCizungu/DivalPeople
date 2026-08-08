# Dival Intelligence Platform (DIP) — Product Requirements

## 1. Product Vision
Dival Intelligence Platform (DIP) is a bilingual (English/French), secure, multi-organization risk and identity intelligence platform designed to become a trusted national reference for responsible business decisions.

DIP enables authorized institutions to search businesses and individuals, consolidate records from approved institutional sources, understand financial exposure, identify risk signals, detect duplicates and anomalies, monitor portfolios, and receive explainable decision-support insights.

DIP is not a replacement for an institution's final decision process. It provides evidence, confidence indicators, risk signals, and recommendations. Authorized human users remain responsible for final lending, onboarding, service, collection, underwriting, or compliance decisions.

## 2. Initial Market Entry
The initial production use case is telecom debt intelligence using datasets already available through the project partnership. Telecom Intelligence Exchange (TIX) is the first industry module of DIP.

Initial path:
1. Ingest existing telecom debt spreadsheets.
2. Normalize and validate records.
3. Resolve business/customer identities.
4. Search and consolidate exposures.
5. Produce auditable intelligence reports.
6. Add participating telecom operators.
7. Introduce governed risk scoring.
8. Expand to banks and other approved industries.

## 3. Primary Users
- Telecom credit and collections teams
- Banks and lenders
- Risk analysts
- Fraud analysts
- Compliance teams
- Enterprise credit-control teams
- Authorized executives
- Data stewards and administrators
- Approved public-sector users where legally permitted

## 4. Core Product Capabilities
### 4.1 Universal Search
Search business or individual records using available approved identifiers:
- Name / legal name
- Customer/account reference
- Phone number
- Business registration identifier
- Tax identifier
- National ID/passport when lawfully available
- Email/address where available and permitted
- Source institution

Search results must display confidence and source provenance rather than silently merging uncertain records.

### 4.2 Business Intelligence Profile
- Canonical organization profile
- Known source records
- Outstanding exposure
- Debt aging
- Active/inactive account status
- Payment/recovery history when available
- Source institutions
- Duplicate/alias candidates
- Risk signals
- Timeline
- Audit history
- Explainable DIP risk assessment

### 4.3 Individual Intelligence Profile
The same platform supports individuals, but personal data requires stricter access controls, lawful-purpose checks, data minimization, and field-level masking.

### 4.4 TIX — Telecom Intelligence Exchange
- Telecom debt exposure
- Aging buckets
- Account history
- Cross-operator matches
- Repeat-default / customer-hopping indicators
- Duplicate records
- Collection status
- Write-off indicators
- Telecom onboarding risk support
- Operator-level dashboards

### 4.5 Data Ingestion
MVP:
- XLSX
- CSV
- Secure manual upload

Later:
- SFTP / managed file exchange
- Scheduled imports
- REST APIs
- Event-based integration

Every import must be versioned, traceable, validated, reversible, and tied to a source organization.

### 4.6 Data Quality
- Required-field validation
- Type/format validation
- Duplicate detection
- Name normalization
- Currency normalization
- Date normalization
- Missing-value reporting
- Invalid aging totals
- Record-level rejection reasons
- Import quality score
- Human review queue

### 4.7 Entity Resolution
Use deterministic rules first, then probabilistic matching.
Examples:
- Exact source customer ID
- Registration/tax identifiers
- Phone/ID matches
- Normalized legal names
- Name + address/phone combinations
- Fuzzy matching

Never automatically merge ambiguous entities without a confidence threshold and review workflow.

### 4.8 Risk Intelligence
Initial risk outputs should be positioned as decision-support indicators, not an official national credit score.

Possible factors:
- Outstanding exposure
- Debt age
- Number of contributing sources
- Payment/recovery behavior
- Repeat defaults
- Account status
- Disputes
- Write-offs
- Identity confidence
- Fraud/anomaly signals
- Data recency

Every score must expose reason codes and model/version metadata.

### 4.9 AI Capabilities
AI is an intelligence assistant, not an autonomous decision maker.
- Risk-summary generation
- Portfolio questions in natural language
- Data-quality explanations
- Duplicate-match explanations
- Collection prioritization suggestions
- Trend summaries
- Executive reporting
- Anomaly triage
- Future predictive repayment/recovery models

AI must not invent source facts. Responses must be grounded in authorized platform data and include source references internally.

### 4.10 Portfolio Intelligence
- Total exposure
- Aging distribution
- Highest exposures
- Risk distribution
- Active/inactive accounts
- Source/operator comparison
- Recovery trends
- New risk events
- Data-quality trends
- High-priority review queue

## 5. Bilingual Requirements
English and French are first-class product languages.
- All navigation, forms, errors, reports, emails, help text, and system messages must be localizable.
- No user-facing strings hard-coded in application components.
- Store canonical data independently of display language.
- User language preference is persisted.
- Reports can be generated in English or French.
- AI assistant responds in the user's selected language.

## 6. National Trust Positioning
The platform should be designed to earn national trust through:
- verified institutional sources;
- transparent provenance;
- strong governance;
- auditable access;
- participant data ownership;
- privacy controls;
- explainable risk methods;
- dispute/correction workflows;
- independent security and model reviews;
- regulator-ready documentation.

Do not market DIP as officially government-endorsed or nationally certified until such status is formally obtained.

## 7. Non-Functional Requirements
- Secure by default
- Multi-tenant
- High auditability
- Encryption in transit and at rest
- Field-level protection for sensitive identifiers
- Backups and disaster recovery
- Observability
- API-first architecture
- Horizontal scalability
- Bilingual UX
- Accessibility
- Data lineage
- Configurable retention
- High availability for production

## 8. MVP Success Criteria
The MVP is successful when an authorized analyst can:
1. Upload a real telecom spreadsheet.
2. See validation results before publishing.
3. Resolve/flag duplicates.
4. Search a business or individual.
5. View consolidated source records.
6. Review exposure and aging.
7. See source provenance.
8. Export an authorized risk/intelligence report.
9. View an immutable audit record.
10. Perform the workflow in English or French.
