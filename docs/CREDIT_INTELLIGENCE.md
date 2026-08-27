# DIP Credit Intelligence

A module specification, for business lending. Not built. This document exists to be argued with
before anything is written, because one of the questions in it may change the data model and one
of them may change whether the module is lawful to operate at all.

**Status: proposed.** Nothing here is in the product. Sections marked *Built* describe what already
exists and would be assembled rather than written.

---

## 1. What it is

A pre-credit and pre-onboarding check that answers one question for a credit officer:

> What does the DIP network know that could materially change my decision about this applicant?

**It does not decide.** A bank has credit policy, internal models and regulatory obligations, and
DIP is an additional intelligence source feeding those — not a replacement for them. This is a
positioning decision and also a legal one: an outside service that effectively determines whether
somebody gets credit is a different regulated thing from one that supplies evidence, and it engages
provisions on automated decision-making that a supplementary source does not.

Every screen and every API response must be readable as *evidence for a human decision*. No
"approve/decline". No threshold that a bank could wire directly into an auto-decline.

---

## 2. What already exists

Most of the proposed screen is an assembly. Naming it precisely so nobody rebuilds it:

| Panel | Where it comes from |
|---|---|
| Identity confidence | `IdentityMatcher` — and it scores the identifier that *matched*, not the one submitted |
| Institutions reporting | `ExchangeService`, counted and never named |
| Qualifying adverse records | `DebtRecord` + retention filter |
| Active disputes | `DebtStatus.DISPUTED` |
| Oldest unresolved obligation | `AgingBand`, aged from the date the obligation fell due |
| DIP Risk Indicator, band, factors | `RiskIndicatorService` |
| Material deterioration | `ChangeGrading` — **still has no tests** |
| View evidence | `EvidencePackService` |
| Add to monitoring | `WatchlistService`, `MonitoringScanner` |
| Post-approval risk-change alert | `MonitoringAlert` — same untested path |
| Subject timeline | `Subject360Service` |
| Contributors named only when permitted | `DisclosureProperties`, ships off |

**Not available today:**

| Panel | Why |
|---|---|
| Cross-industry relationships | `tix_subject.sector` exists (V32) and an import mapping can name a sector column (V33). Nothing populates it. |
| Observed / performing / past due | See § 3. There is no representation of a relationship that is performing. |
| Payment performance % | Requires § 3. |
| Credit intelligence report (PDF) | Not built. |
| Bank-to-bank API | Not built. Authentication is browser sessions only; see § 7. |

---

## 3. The structural change: obligations have a life, not just an end

**This is the whole proposal. Everything else is presentation.**

Today a record comes into existence *by declaring a default*. There is a reporting threshold that
refuses anything under 100 USD. A relationship that is performing normally has nowhere to live, so
the network holds only bad news — and the two applicants below are indistinguishable to it:

| | Defaults | Obligations observed | Reads as |
|---|---|---|---|
| Company A | 1 | 2 | 50% problematic |
| Company B | 1 | 48 | 2% problematic |

They are not the same borrower and DIP currently cannot tell them apart. Worse, it cannot tell
either of them apart from a company with one default and no other history at all.

### Proposed model

Two tables replace the single record:

```
tix_relationship        one account or facility, held by one operator against one subject
tix_relationship_event  what happened to it, dated, append-only
```

Events, from the lifecycle in the proposal:

```
OPENED  PERFORMING  PAID_AS_AGREED  LATE_30  LATE_60  LATE_90_PLUS
RESTRUCTURED  DEFAULTED  SETTLED  CLOSED  DISPUTED
```

**Current status is derived from the events, never stored.** This is the same rule the provenance
spine already follows and it is the one that makes the history trustworthy: a status column can be
edited to say a company always paid on time, whereas an append-only event log has to be
contradicted rather than rewritten. It also makes "payment performance" a computation over evidence
instead of a number somebody typed.

**Migration.** An existing `DebtRecord` becomes a relationship with one `DEFAULTED` event on its
default date, plus `SETTLED` where it was settled. Nothing is lost and nothing is invented — the
history simply starts sparse, which is honest.

**The reporting threshold stops making sense.** It exists to keep trivial amounts out of a
blacklist. A record of an obligation *paid as agreed* has no such risk, so a floor on positive
events would only make well-behaved small accounts invisible — which is backwards. The threshold
should be scoped to adverse events and removed from the rest.

### What this costs

- Volume changes by orders of magnitude. Today: rows for defaulters. Then: rows for every
  subscriber with an account. The ingest path already handles 4,290-row files; it has not been
  measured against a monthly full-book delivery from a telecom.
- Retention becomes a per-event-class decision (§ 4).
- The risk model has to be rebuilt (§ 5).
- The legal basis for processing changes (§ 6). **This is not a schema question.**

---

## 4. Retention

Today: an adverse record expires and stops being visible; `RetentionPurge` erases it. One rule,
one clock.

Positive history breaks the symmetry, and the answer is not obvious in either direction:

- A **short** retention on positive events destroys the feature. "48 obligations paid as agreed"
  is only meaningful over years.
- A **long or indefinite** retention on positive events means DIP holds a permanent behavioural
  record of every subscriber of every participating institution. For a business that is defensible.
  For a person it is a significant processing footprint that has to be justified, disclosed and
  exercisable against.

**Recommendation for the spec to argue:** retention is set per event class, adverse events keep
today's rule, positive events get a longer but *finite* window, and both are configuration with
provenance — as § Settings already does for the current rules. **Question for Olivier**, not a
decision to make in code.

---

## 5. The risk model

`RiskInputs` today is entirely negative: `anyOutstanding`, `institutionsWithOutstanding`,
`longestOverdueDays`, `fraudSignalCount`. Adding positive history means ratios, not just presence.

Two traps worth writing down before anybody tunes a weight:

**A ratio punishes the thin file.** A company with two obligations and no defaults has a perfect
record and almost no evidence. A model that scores it like a company with 200 clean obligations is
overconfident; one that scores it as risky penalises exactly the borrower DIP exists to help — the
business with no bank history in a market where private credit is scarce. The indicator should
carry an explicit **evidence depth**, and thin files should widen a band rather than lower a score.

**Positive history from one institution is not a portfolio.** Forty clean months with one telecom
says something narrower than forty clean months across a telecom, a utility and a supplier. The
model already counts institutions for adverse signals; it should do so for positive ones.

---

## 6. Regulation — settle this before building

Credit information bureaus in the DRC are subject to licensing and authorisation by the **Banque
Centrale du Congo**, which also runs the **Centrale des Risques** that credit establishments report
into under Instruction n° 5.

Today DIP is a sector exchange between telecom operators. **The moment it captures positive payment
history and sells intelligence to supervised banks, it is functionally a *bureau d'information sur
le crédit*.** That is a supervised activity, and the answer is likely to dictate the data model,
the retention rules, the subject-rights process and who may be a participant.

Questions to put to Olivier, and probably to the BCC:

1. Does DIP, serving banks with cross-institution credit intelligence, require BCC authorisation as
   a bureau d'information sur le crédit? At what point does it cross the line — positive data,
   bank participation, or selling to supervised institutions?
2. If authorisation is required, what does it impose on retention, on subject access, and on fee
   schedules? (The regulations contemplate homologation and publication of bureau tariffs.)
3. Does a bank's participation in DIP interact with its Centrale des Risques obligations —
   complementary, or is there a reporting conflict?
4. What legal basis covers reporting positive payment history? Consent, contract, legitimate
   interest — and does the answer differ for a SARL and for a person?
5. Does a DIP Risk Indicator supplied to a lender engage the Code du numérique's provisions on
   automated decision-making, given that the bank makes the decision?

None of this blocks a **demo built on existing data**. All of it blocks shipping positive-history
capture to a real institution.

### Positioning follows from it

Banks already report to the Centrale des Risques, so "a better risk central" is a weak pitch and
invites an unflattering comparison with the regulator's own system. The strong line is the
opposite:

> **We see what your risk central cannot.** Telecom, utility and trade-credit behaviour on
> businesses that may have no bank history at all.

In a market where private-sector credit is a small share of GDP, the applicant a bank cannot price
is the one with no file. DIP is the only place with anything on them. That is the sentence to take
to Rawbank, EquityBCDC, Ecobank, Access Bank and UBA.

---

## 7. The bank-facing API

The eventual integration is machine to machine:

```
Loan application → bank core → DIP Credit Intelligence API → bank's own decision engine
```

DIP has no such surface today. Authentication is a browser session — an opaque cookie against a
Redis-backed BFF (ADR 0003). A bank integration needs:

- **Client credentials or mTLS**, per institution, issued and revocable.
- **A stated purpose on every call**, carried into the audit trail exactly as the screen already
  requires of a human. An API is not an exemption from saying why.
- **Rate limits per institution**, extending `InquiryRateLimiter`, and anomaly detection —
  `modules/anomalies` already watches for enumeration by humans and would need to watch machines.
- **A version contract.** A bank wires this into a core system; the response shape becomes an
  interface that cannot be changed at will. `Subject360` already carries a `viewVersion`; the same
  discipline applies here and matters more.

---

## 8. Reciprocity

Not currently modelled, and every functioning credit bureau depends on it: **what an institution
may read should depend on what it contributes.** A bank that queries and reports nothing is taking
the value telecoms created and returning none.

This is a product and commercial rule with a technical expression — a contribution measure per
participant, and a disclosure tier derived from it. Worth designing early, because retrofitting
reciprocity onto participants who joined without it is a commercial conversation rather than a
migration.

---

## 9. Definitions that must be pinned down

The mock uses terms that read as precise and are not yet defined anywhere. Each needs a rule before
it appears on a screen a credit officer relies on:

- **Qualifying adverse record** — qualifying by what? Amount, age, not-disputed, within retention?
- **Cross-industry relationship** — requires the sector field to be populated, which requires
  operators to map it on import.
- **Observed payment performance** — over what window, weighted how, and what happens when the
  denominator is small (§ 5).
- **Material deterioration** — `ChangeGrading` defines this today. It is the only new backend logic
  that shipped without tests and it runs nightly on the deployed instance.

---

## 10. Sequence

1. **Answer § 6.** It is the only item that can invalidate the rest.
2. **Test `ChangeGrading` and the monitoring alert flow.** Deterioration detection is load-bearing
   for this module and currently unproven.
3. **Build the demo on existing data.** Identity, institutions, adverse records, disputes, aging,
   risk indicator, evidence, add-to-monitoring, and a material-change alert. The payment-performance
   panel is shown as *not yet available* rather than filled with an invented number.
4. **Define the lifecycle model** (§ 3) and migrate existing records into it.
5. **Rebuild the risk model** around depth and breadth of evidence (§ 5).
6. **Then** the API and reciprocity.

Steps 1–3 are weeks. Steps 4–6 are the real product and should not be started before step 1 has an
answer.

---

## 11. Demo script — business lending, five minutes

Built entirely from step 3, so it can be true on the day it is given.

1. **A business applies for a facility.** Enter the RCCM. Identity resolves with a confidence score,
   and the screen says which identifier matched.
2. **The network answers.** Institutions reporting, adverse records, active disputes, oldest
   unresolved obligation. No amounts, no institution names — say out loud that this is deliberate,
   because a bank in the room is also a future contributor and wants to know its own book is safe.
3. **The indicator, with its factors.** Every factor traceable to a record.
4. **View evidence.** The pack, and the absences it declares — including that no model produced it.
5. **Approve, and add to monitoring.**
6. **Six months later** — a material change alert: the indicator moved, a new adverse relationship
   appeared, the borrower is in the commercial lending portfolio.

The close is the honest one: *this is what the network can tell you today with four telecoms in it;
here is what it tells you with four banks in it too.*

---

## Sources

- [Instruction n° 5 aux établissements de crédit relative à la Centrale des Risques — BCC](https://www.bcc.cd/system/files_force/dsif/instruction_ndeg5_aux_etablissements_de_credit_relative_a_la_centrale_des_risques.pdf/?download=1)
- [Le cadre légal et règlementaire des activités financières en RDC](https://www.village-justice.com/articles/cadre-legal-reglementaire-des-activites-financieres-rdc,46461.html)
- [Banque Centrale du Congo — réglementation, établissements de crédit](https://www.bcc.cd/surveillance-des-intermediaires-financiers/reglementation/textes-reglementaires/etablissements-de-credit/societes-financieres)
