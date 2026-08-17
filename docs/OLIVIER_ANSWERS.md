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

### 2. He wants the amount disclosed — partially done, and deliberately less than he asked

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

**Half of it is now built, and the half that is built is the conservative half.** The risk
indicator weighs the total in four bands a decimal order of magnitude apart — 1k, 10k, 100k — worth
at most 10 points out of 100. An enquirer learns which of four brackets applies; nobody learns a
figure, and no sequence of readings recovers one, which is the property that makes weighing it safe
at all.

Reporting the amount itself, as he asked, is still **not built and still wants your decision**. The
trade has not changed: an amount plus an institution count is a great deal more than a status, and
a bank asking about a company it already knows can difference two answers over time into a rival's
billing. His protection — not naming the operator — does not prevent that when only one institution
reports.

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
- ~~Soften a conflicting RCCM from decisive to advisory; keep a conflicting tax number heavier.~~
  **Done.** The register number is now its own signal and reads asymmetrically: agreement is worth
  0.50 as before, disagreement only −0.15. The case it was written for — one exact business name,
  two register numbers, nothing else — moved from 0.0 (invisible for ever) to 0.45 (a person
  looks). A conflicting tax number still clamps the pair back to zero, which is why the two had to
  be split rather than softened together.
- ~~Add sector and address to the subject, and to the import template below.~~ **Done** — V32 puts
  sector, city and street on the subject; V33 lets a mapping name the columns they arrive in; the
  template asks for all three.
- ~~Add both as match signals, replacing two of the three that currently read *unavailable*.~~
  **Done.** Three signals rather than two, city having been split from the street because one
  compares as an equality and the other cannot. One *unavailable* signal is left — a second contact
  number, which no delivery carries.

> Worth being plain about what the softening does and does not buy. It does not make the matcher
> better at telling one company from two — on a name and a register number alone it cannot, and it
> was previously resolving that uncertainty by always guessing "two". More pairs now reach the
> queue, including pairs a reviewer will dismiss. The information that would actually separate the
> two cases is sector and operating address.
>
> Both are now built, and the arithmetic closes: two "Grand Horizon SARL" with different register
> numbers, in different cities and different trades, score 0.05 and never reach the queue. The same
> pair with matching sector, city and street scores 0.83 and does. Neither number was reachable
> before, in either direction.

---

## Settled, and cheap to apply

| Question | Answer | What changes |
|---|---|---|
| **Currency of `Balance`** | **USD**, both files | **Applied.** `OUTSTANDING_EXPOSURE` is assessed at 10 points in four bands; the model is `DIP-RI-3` and the ceiling is back at 100. The weight is small deliberately — see below. |
| **Reporting threshold** | 100 USD is right | **Applied.** Provenance in Settings is now *advised by counsel* rather than *terms of reference*. |
| **Retention** | Apply 5 years | **Applied as 5 for both.** Which means a repeat default is now kept no lifetime longer than a first one, so that distinction currently decides nothing — the two settings survive in case he wants one. `settled-days` was never asked about and is still the only *unverified placeholder* on the settings screen, which makes it much easier to see. |
| **Rights deadlines** | **10 days** for access, **20** for everything else | **Applied.** A case keeps the deadline it was given when raised — `due_at` is written once and V23 forbids updating it — so this binds new requests and does not retroactively make the existing queue overdue. New cases will go overdue far faster and the front door will show it. |
| **Article 214 scope** | Confirmed | Notifying every institution that enquired before the correction is right. No change. |
| **Write-off status** | Accounting only; **the debt remains recoverable** | Closes an open question on the roadmap. Declaring the Vodacom write-offs is legitimate, and all 4,290 rows stand. |
| **Prior authorisation** | Not required, in his estimation | Note the hedge — *j'estime*. Not a clearance. |
| **Legal persons** | **B2B first, individuals later** | The pilot is businesses. Individuals stays built and empty, which is now the right state rather than a gap. The person-weighted half of the match scorer is for later. |
| **Aged balance** | Use the **+360 day** line | Still needs confirming for the files already imported — if it means the >360 bucket rather than `Balance`, every imported amount is wrong, and exposure now carries risk weight resting on those amounts. **Check before the next import.** For files that arrive on the new template the question is moot: it asks for the amount and the due date, and DIP ages it. |

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

**Done — `docs/MODELE_IMPORT_DIP.xlsx`**, with the reasoning in `docs/IMPORT_TEMPLATE.md`. Twenty-
three columns in French, of which seventeen are used today and six are marked *Bientôt* on the
notice sheet, which says plainly that they are not yet exploited and their contents are not
retained. Asking for a field and silently dropping it would be worse than not asking.

Three sheets: the table to fill in with two example rows, a column-by-column notice, and the list of
what DIP refuses and whether the refusal costs a row or the whole file.

It asks for the **date the obligation fell due** and no aging-bucket columns at all, because DIP
computes the ageing itself — which makes the open `+360` question below stop existing rather than
needing an answer.

---

## Still unanswered

Drafted as `docs/EMAIL_OLIVIER_TEMPLATE.md`, to travel with the template rather than a week after
it. Four questions carried into that email, and the rest left for the operators:

- **Naming the creditor, not just the amount.** You asked whether the amount owed may be disclosed
  to other institutions. The Subject 360° profile makes the question concrete and slightly larger:
  the screen can now name the operators reporting a subject *and* price each position. Both are
  behind switches and both ship **off**, so today the screen still says "three institutions report
  this company" and refuses to say which. Two things before either is turned on — your answer, and
  the agreement of the operators whose books become visible. They joined on "never which, never how
  much", and that sentence is the reason a competitor was willing to put its receivables ledger
  into a shared database.
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
