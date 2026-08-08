# Dival Intelligence Platform — Roadmap

**Rewritten 8 August 2026.** The previous version described an HR product called Dival People with
a telecom exchange as an optional phase-9 integration. That is no longer what is being built, and
a roadmap that contradicts the landing page is worse than no roadmap.

DIP is a **national risk and identity intelligence platform** for the DRC: approved institutions
contribute records, resolve identities, and consult risk intelligence about businesses and
individuals, while retaining ownership of their operational data. TIX — the telecom exchange — is
the first industry edition, not a bolt-on. HR (**Dival People**) becomes a separate product and is
paused.

Nothing below is a commitment to a date. It is an ordering, and an honest account of what exists.

---

## What actually exists today

This section matters more than the phases. Everything on the landing page reads as though it is
built; almost none of it is. Anyone using this document to plan should start here.

| Capability | State |
|---|---|
| Multi-tenant platform: tenants, auth, roles, audit, EN/FR, files, notifications | **Built**, with row-level security proved by tests |
| Identity resolution — exact match on normalised identifiers | **Built** (`SubjectResolver`), write path only |
| Identity matching with confidence — read path | **Built** (`IdentityMatcher`), scores the identifier that matched |
| Debt declaration by an operator | **Built** — `POST /api/v1/tix/debt-records`, with a reporting threshold |
| Cross-operator inquiry returning a status, never another operator's data | **Built**, rate-limited and audited |
| Retention, expiry and erasure | **Built** — periods are placeholders, see `TIX_RETENTION.md` |
| Audit trail, append-only by database rule | **Built** |
| Subject rights: access, rectification, erasure on request | **Not built** — required by the TDR |
| Dispute raised by the subject | **Wrong way round** — the endpoint currently requires an operator role |
| Universal entity search across sources | **Not built** — the landing page shows a mock |
| Risk score / rating with contributing factors | **Not built** — the score on the page is invented |
| Fraud and anomaly detection | **Partly** — reused-identifier signals only |
| Portfolio monitoring and dashboards | **Not built** |
| File ingestion (Excel/CSV) and validation pipeline | **Not built** |
| Public API for participating institutions | **Not built** |
| AI assistant | **Not built** |
| Bank, insurance, utilities, enterprise, public-sector editions | **Not built** — TIX only |
| HR modules (employees, leave, payroll, performance, learning, recruitment) | **Built and paused** — kept green, not extended |

---

## Phase A — The exchange works end to end (current)

Make one industry edition genuinely usable before generalising. Everything here is TIX.

- ~~Declaration API with a reporting threshold~~ — done
- ~~Retention, expiry, and real erasure~~ — done
- **Subject rights**: access, rectification, erasure on request, and moving dispute to the person
  it belongs to. Includes the hard part: authenticating somebody who is not a user of any
  participating operator's system.
- **Legal basis and retention periods** checked against the Code du numérique
  (Ordonnance-loi 23/010), recorded in `TIX_RETENTION.md` rather than assumed.
- **Ingestion**: the Kinshasa spreadsheets, validated and reconciled, not hand-entered.
- The CDF reporting threshold, which needs a rate somebody owns.

**Exit:** an operator can load its real book, declare, inquire, settle, and answer a subject who
asks what is held about them — and can show an auditor the trail for all of it.

## Phase B — Search and evidence

The capabilities the landing page leads with, in the order they become possible.

- Universal entity search across participating sources
- A transparent risk profile: the factors and their weights, published alongside any score
- Business and individual profiles as distinct things, with distinct permissions
- Fraud signals beyond identifier reuse

**Exit:** a risk report a bank would accept as evidence, where every number can be traced to a
contributed record.

## Phase C — A network rather than a database

- Real-time APIs for participating institutions
- Provenance retained per record, so "who said this" is always answerable
- Governance: joint controllership, the sharing agreement, competition-law safeguards
- Portfolio monitoring and dashboards

**Exit:** a second operator contributing through the API, and a governance document both have
signed.

## Phase D — Second industry

Banking or utilities, whichever has a real counterparty first. The test of the architecture is
whether the second edition costs materially less than the first.

## Phase E — AI assistance

Summarising evidence and navigating portfolios. Decisions stay with authorised people — this is
already a hard rule in `AGENTS.md` and it does not soften because the feature is popular.

---

## Dival People (HR)

Built through payroll and self-service, tested, paused. Not deleted, not extended, kept green. It
becomes its own product with its own roadmap when there is someone to sell it to; the Kinshasa
team that asked for it is still waiting.

## The honest risks

- **The periods, thresholds and legal basis are unverified.** Plausible placeholders are the
  dangerous kind — they get treated as decisions.
- **Competition law.** A shared blacklist among competitors is antitrust exposure, and the TDR
  flags it twice. Rate limiting and refusals that do not confirm existence are part of the answer;
  a governance framework is the rest, and it is not written.
- **The landing page is ahead of the product**, deliberately, as a prototype. Every invented
  figure on it is labelled illustrative. That labelling is load-bearing and should not be removed
  for looking untidy.
- **No production deployment, no design partner, no signed agreement.** The TDR commissions a
  feasibility study; it does not commission this.
