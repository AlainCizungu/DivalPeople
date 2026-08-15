# Olivier's answers, and what each one changes

Received August 2026, in reply to `EMAIL_OLIVIER_CLARIFICATIONS.md`. His words are summarised;
the consequences for the platform are ours and are the reason this file exists rather than a copy
of the email sitting in somebody's inbox.

Read this before changing anything that depends on it. Several answers reverse a decision the code
currently makes, and one of them reverses a decision the whole exchange was designed around.

---

## The three that matter most

### 1. The lawful basis is consent, and nothing can withdraw it

He does not confirm the article 192 reading. His answer is that telecoms and banks already have
contracts letting them use their customers' data, and that AJF works under mandate and a
non-disclosure clause. Question 3 settles the alternative: **a telecom has no legal obligation to
report unpaid accounts.**

So the basis is contractual consent. Which leaves the question asked in the email unanswered, and
it is the one that matters: **what happens when somebody withdraws consent while the debt is still
unpaid.** A consent that cannot be withdrawn without penalty is not a consent, and that is exactly
the objection an authority raises.

The platform has no withdrawal path today. Erasure exists and is refused for an outstanding debt —
which is defensible under a legal obligation and much harder to defend under consent.

**Not a code change yet. A question to put back to him before the pilot**, because the answer
decides whether the erasure refusal stands.

### 2. He wants the amount disclosed

> *"l'idée est juste d'indiquer par exemple qu'un débiteur doit XXX montant à une société TELECOM
> sans indiquer laquelle"*

The exchange currently discloses an outcome, a set of statuses, and how many institutions report —
**never an amount**. That was a deliberate design decision, defended in `InquiryResult` and in the
institution-count tests, on the grounds that an amount tells a competitor the size of a rival's
commercial relationship.

He is the lawyer and this is his call to make, but the trade should be made with open eyes: an
amount plus an institution count is a great deal more than a status, and a bank asking about a
company it already knows can difference two answers over time into a rival's billing. His
protection — not naming the operator — does not prevent that when only one institution reports.

**Wants a decision from you before it is built.**

### 3. An RCCM changes, so it cannot be decisive

> *"il arrive qu'une entreprise modifie son RCCM en cas de modification des statuts ou d'ajout du
> capital social"*

This is a correction to the match scorer and a valuable one. `MatchScorer` currently treats two
different RCCM numbers as **−0.60, heavier than any agreement** — enough on its own to push a pair
out of the review queue. If a company's registration number legitimately changes, that rule
actively prevents the platform from ever noticing the two records are one company.

He proposes matching on four things together: **dénomination, secteur d'activité, adresse (zone
opérationnelle), RCCM and/or tax number.**

Two of those the platform does not hold at all. Sector and address are not fields on a subject, and
they are the same two the resolution screen already reports as *never held* — so his answer and the
screen's own admission are the same finding arrived at from two directions.

**Code changes needed:**
- Soften a conflicting RCCM from decisive to advisory; keep a conflicting tax number heavier.
- Add sector and address to the subject, and to the import template below.
- Add both as match signals, replacing two of the three that currently read *unavailable*.

---

## Settled, and cheap to apply

| Question | Answer | What changes |
|---|---|---|
| **Currency of `Balance`** | **USD**, both files | The largest open question in the product, closed. `OUTSTANDING_EXPOSURE` can stop being *Not assessed* — a risk model change, so `DIP-RI-3`, and the ceiling moves back up. Settings stops calling the threshold's currency unconfirmed. |
| **Reporting threshold** | 100 USD is right | Provenance in Settings moves from *terms of reference* to a decision somebody has taken. |
| **Retention** | Apply 5 years | Today: 3 years simple, 5 for a repeat. Wants 5. Settings stops marking these *unverified placeholder*. Worth one clarifying question: 5 for both, or 5 as the single period? |
| **Rights deadlines** | **10 days** for access, **20** for everything else | Today: 60 and 30. A large tightening. The overdue and due-soon counts on the front door will change shape immediately, and a queue that was comfortable becomes a queue with real pressure on it. |
| **Article 214 scope** | Confirmed | Notifying every institution that enquired before the correction is right. No change. |
| **Write-off status** | Accounting only; **the debt remains recoverable** | Closes an open question on the roadmap. Declaring the Vodacom write-offs is legitimate, and all 4,290 rows stand. |
| **Prior authorisation** | Not required, in his estimation | Note the hedge — *j'estime*. Not a clearance. |
| **Legal persons** | **B2B first, individuals later** | The pilot is businesses. Individuals stays built and empty, which is now the right state rather than a gap. The person-weighted half of the match scorer is for later. |
| **Aged balance** | Use the **+360 day** line | Needs confirming against the file: it may mean the >360 bucket is the figure to report rather than `Balance`. If so, every imported amount is wrong. **Check before the next import.** |

---

## The deliverable he is asking us for

> *"c'est au concepteur de l'application d'envoyer un modèle de tableau qui contient tous les
> éléments et rubriques à compléter colonne par colonne"*

He wants **DIP to publish the import template**, which AJF will then take to the operators. Both
telecoms have agreed to adapt their exports to it.

This inverts the problem the platform has been solving. Everything built so far — the profiler, the
operator-defined mapping, identity by name, account references — exists because the files arrive in
whatever shape an operator's billing system produces. A template does not make that work useless,
because deliveries will still arrive imperfect, but it means **the next file can carry the fields
the platform actually needs**, including the two his answer to question 3 asks for.

The template should carry, at minimum: legal name, sector, operational address, RCCM, tax number,
the operator's own account reference, amount, currency, the date the obligation fell due, the
as-at date of the export, and dunning evidence.

**This is the highest-leverage thing on the list.** It is a document rather than code, and it
decides what every future import can do.

---

## Still unanswered

Worth a second, much shorter email rather than assumed:

- **Consent withdrawal** while a debt is unpaid — see above.
- **Dunning** — was the contractual reminder process actually run on all 4,290 accounts? The
  platform requires that attestation and records who gave it.
- **Individuals in the files** — he says B2B first, but did not say whether the current files
  contain any people. If they do, they are being treated as companies today.
- **Credit balances** — negative amounts are refused and reported. Is that right?
- **`PWC`, `Descoped`, `Vaccounts`** — still unexplained. `Vaccounts` is 1 on every row.
- **48 homonyms in Vodacom's own book** — one customer with several accounts, or different
  companies? This is entity resolution inside a single operator, and it is the first real work the
  resolution queue would have.
- **Orange's unlabelled columns, `0`, `#`, `GSM`, `No GSM`** — the template may make these moot.
