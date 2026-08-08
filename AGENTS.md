# AGENTS.md — Dival Intelligence Platform

You are working on DIP: a bilingual, multi-tenant, security-sensitive institutional intelligence
platform for the DRC. Approved institutions contribute records, resolve identities, and consult
risk intelligence about businesses and individuals while retaining ownership of their operational
data. **TIX** — the Telecom Intelligence Exchange — is the first industry module, and the only one
that exists.

The people in this database did not ask to be in it. Almost every rule below follows from that.

Before coding, read `docs/PRODUCT_REQUIREMENTS.md`, `docs/ARCHITECTURE.md`,
`docs/DATABASE_DESIGN.md`, `docs/SECURITY_MODEL.md`, `docs/API_STANDARDS.md`,
`docs/DATA_GOVERNANCE.md`, `docs/DEVELOPMENT_RULES.md`, `docs/TESTING_STRATEGY.md`,
`docs/UI_DESIGN_SYSTEM.md`, `docs/INTERNATIONALIZATION.md`, `docs/ROADMAP.md`.

**Read `docs/ROADMAP.md` first.** It opens with what is actually built, which is much less than
these documents describe.

## MVP priority

Real telecom data ingestion → data quality → entity resolution → universal search → TIX →
explainable risk intelligence.

## Non-negotiable

1. Multi-tenant isolation.
2. Server-side authorization on every endpoint.
3. Audit sensitive reads.
4. Immutable raw imports.
5. Preserve data provenance.
6. Reversible entity merges.
7. English and French, both first class.
8. Human review for consequential risk decisions.
9. Explainable, versioned risk outputs.
10. No production PII in prompts, tests, logs, or source control.
11. AI cannot bypass permissions.
12. Do not claim official national or government endorsement in product copy without evidence.

**Rules 4, 5, 6 and 9 are not true of the code today.** There is no import batch, no raw record,
no source link, no merge, and no risk model. Treat them as constraints on what you build next, not
as descriptions of what is there — and see `docs/DATABASE_DESIGN.md` for exactly how the schema
differs.

## What is actually enforced

Rules that a machine checks, so they cannot quietly stop being true. `scripts/check_architecture.py`
runs in CI:

1. `common/` never imports `modules/`.
2. No module reaches into another module's repositories.
3. Every tenant-owned table has a row-level security policy.
4. No fixed-width `CHAR` columns.
5. **Every endpoint declares an authorization annotation.** Omission is permissive — the security
   config ends with `.anyRequest().authenticated()`, so an endpoint with no `@PreAuthorize` is
   reachable by every signed-in user. This rule exists because 52 endpoints were like that.

Also enforced: EN/FR message-key parity, backend tests with Testcontainers, frontend typecheck and
build, and the backup script's failure paths.

## Working agreement with the AI assistant

**The assistant cannot compile or run Java.** No JDK, no Gradle, no network in its sandbox. It
therefore:

- says plainly, in every commit and message, that **the Java is NOT compiled**;
- verifies what it can without a compiler — comment-aware brace and paren balance, cross-class
  symbol existence *and arity* *and accessibility*, enum constants, JPQL property names against
  entity fields, SQL and YAML parsing, EN/FR parity, arithmetic checked independently in Python;
- never claims a test passed unless a human ran it and reported the output.

Three classes of defect only a real run finds, and they keep recurring: Spring context startup,
Flyway and bean ordering, and anything Docker. Expect them.

A recurring failure worth naming: checks that match **text** rather than **meaning** — a constant
that exists but is out of scope, a method that exists but takes different arguments, a symbol that
exists but is not visible from the caller, a word that appears only in a comment. Strip comments
and resolve the language before asserting anything about source.

## Definition of done

Requirements met; authorization implemented server-side; tenant isolation tested; bilingual;
validation and audit added; tests written **and run by a human**; documentation updated.

## Testing, in one line

Ask from the wrong side. A suite that only ever asks as the person entitled to an answer proves
nothing about refusals — 300 green tests missed 52 unguarded endpoints. And `@Transactional` tests
are structurally blind to anything that only fails at commit.

## Dival People (HR)

Withdrawn. The screens are gone; the backend modules and migrations V5 and V8–V17 remain, still
tested, and come out in their own change. Do not extend them, do not break them, and do not delete
migrations — Flyway validates checksums against every database that has already run them, so
removal means a forward migration that drops tables, never an edit to history.
