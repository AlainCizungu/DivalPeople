# TIX — Telecom Information Exchange

## Purpose
TIX is the customer risk and identity exchange capability of the Dival People **Telecom edition**. It allows participating telecommunications operators to verify customer identity, share verified debt information, detect fraud, and make consistent onboarding decisions.

TIX is **not a blacklist**. It is a decision-support system. Every response is an indicator accompanied by evidence and confidence; the operator makes the decision.

## Position in the platform
TIX is a module of the modular monolith (`modules/tix`), built on the same DIP foundation as every other module: tenancy, authentication, authorization, audit, localization, and notifications. It does not introduce a separate service, database, or deployment.

Each participating operator is a **tenant**. The exchange itself is the cross-tenant boundary, and it is the only place in the platform where data deliberately crosses tenants — through explicitly audited exchange services, never through ordinary tenant-scoped queries.

## Core concepts

| Concept | Description |
|---|---|
| `Subject` | A person or business that may be verified. Holds identity attributes, never operator account details. |
| `SubjectIdentifier` | A single identifying attribute (MSISDN, national ID, passport, driver license, voter card, RCCM) attached to a subject. |
| `DebtRecord` | A qualified unpaid obligation declared by an operator against a subject. |
| `Inquiry` | A verification request by an operator, always recorded with actor, purpose, and result. |
| `Dispute` | A subject's formal contestation of a debt record. Suspends the record from inquiry results while open. |
| `FraudSignal` | A detected indicator (duplicate registration, reused document, velocity anomaly) with severity and confidence. |

## Debt status model

| Status | Meaning |
|---|---|
| `OUTSTANDING` | Confirmed unpaid obligation exists |
| `SETTLED` | Previously declared obligation fully resolved |
| `DISPUTED` | Contested by the subject; provisional pending review |
| `UNDER_INVESTIGATION` | Associated with an open fraud investigation |
| `CLEARED` | No adverse record held |

## Declaration rules
- A debt record may be declared only above the configured qualifying threshold and only after the contractual dunning period has elapsed.
- The declaring operator must record evidence that dunning took place.
- The declaring operator owns the record and is solely able to mark it settled.
- Declaration is free of charge to participants; registry completeness is the value of the exchange.

## Inquiry rules
- Inquiries return a **normalised risk indicator**, never another operator's raw account data.
- Tariffs, consumption, and commercial information never enter the exchange.
- Every inquiry records: actor, tenant, timestamp, identifiers submitted, purpose, match confidence, and result.
- Access is restricted to the credit-decision and fraud-prevention purpose scopes, enforced server-side.

## Identity matching
Matching combines deterministic rules on strong identifiers with probabilistic comparison across name, date of birth, and secondary attributes. Every match carries a confidence score, and responses below the review threshold are returned as `REVIEW_REQUIRED` rather than as a match.

Matching is assistive. It never silently merges subjects; merges require human confirmation and are audited.

## Subject rights
Access, rectification, and erasure requests are first-class workflows, not support tickets. Retention is enforced automatically: three years for a single default, five for repeat defaults, and immediate status update on regularisation. Country-specific legal review is required before production.

## AI boundaries
Consistent with `AGENTS.md` and `SECURITY_MODEL.md`, AI in TIX may score identity similarity, estimate default likelihood, and surface fraud indicators. It may **not** independently establish fraud, reject a customer, or modify a debt record. Model output is labelled advisory and audited.

## Delivery phases
1. Subjects, identifiers, deterministic matching, audit
2. Debt records, declaration and settlement, disputes
3. Inquiry API and operator console
4. Probabilistic matching and fraud signals
5. Predictive default risk and decision assistant
6. Cross-sector expansion beyond telecommunications

## Open questions
- Qualifying debt threshold per market
- Legal basis and regulator engagement per country
- Commercial model for participation
