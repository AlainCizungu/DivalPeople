# TIX retention — what is implemented, and what is not yet decided

**Status: the mechanism is built and the numbers are placeholders.**

> **Update, 8 August 2026.** The Code du numérique has now been read — see
> [`TIX_LEGAL_BASIS.md`](TIX_LEGAL_BASIS.md). Two findings land directly here.
>
> **There is no statutory retention period.** Article 193(3) bounds retention only by necessity
> relative to purpose. So no amount of further reading will produce a number to point at; what the
> law requires instead is a written justification for whichever number is chosen, and article 216
> adds a duty to review it periodically. The gap in item 1 below is therefore not "find the figure"
> but "write the reasoning" — a smaller task and a harder one.
>
> **The lawful basis is narrower than assumed.** Article 192 offers consent or a legal obligation,
> and nothing else. If the basis is consent, article 215(5) obliges erasure the moment it is
> withdrawn, regardless of any period configured here — which would make the periods below
> unenforceable rather than merely unverified.

The exchange now has a retention clock, excludes expired records from inquiries, and erases them
nightly. What it does not have is a defensible answer to "why three years". This document exists so
that gap is visible rather than implied by a configuration file.

## What is implemented

| Case | Period | Where |
|---|---|---|
| First default, unsettled | 3 years from the **default date** | `dip.tix.retention.simple-years` |
| Default by somebody the exchange has seen before | 5 years from the default date | `dip.tix.retention.repeat-years` |
| Settled | 30 days from settlement, or the existing expiry if sooner | `dip.tix.retention.settled-days` |

Three design decisions are worth knowing because they are not obvious from the numbers:

- **The clock runs from the default date, not the declaration date.** Otherwise retention measures
  how long an operator took to report rather than how old the default is, and re-declaring would
  reset it. This is also why declaration refuses a future default date.
- **Récidive is judged once, when the record is written, and never recalculated.** A later default
  gives the *new* record the longer period; it does not reach back and extend existing ones.
  Retroactively lengthening how long already-recorded facts are held is punishment implemented as
  a cron job.
- **Settlement can only shorten.** Paying a debt always brings erasure closer and never pushes it
  away.

Erasure is deletion. Expired debt records are removed, and a subject is removed once no operator
holds any record against them — a name, date of birth and national ID number left behind after the
records are gone would be personal data with no lawful basis and nothing explaining why it is held.
Each erasure writes a `TIX_RECORD_ERASED` audit row carrying the record id and nothing about the
person, so erasure can be evidenced without the audit log becoming where the erased data survives.

## What has not been decided

These are the open questions. All of them are the feasibility study's to answer, and the TDR asks
for exactly this analysis under *faisabilité juridique et réglementaire*.

1. **The periods themselves.** 3 and 5 years are the TDR's own illustrative figures, offered as
   examples. They have now been checked against the Code du numérique (Ordonnance-loi 23/010 of
   13 March 2023, in force 11 April 2023) and **the Code sets no period at all** — article 193(3)
   requires only that data be kept no longer than necessary for the purpose. The figures are
   therefore not wrong; they are unjustified, and the justification is the deliverable. They remain
   plausible numbers, which is the dangerous kind — plausible placeholders get treated as decisions.
2. **Whether a settled debt should be visible at all.** The TDR asks for erasure *après
   régularisation*. The implementation keeps a settled record visible for 30 days so the settling
   operator can reconcile, which is a reading of "after regularisation" and not the only one. The
   stricter reading is that paying makes you clear immediately and other operators should see
   nothing. That is a policy call, not a technical one.
3. **The lawful basis.** The TDR asks for identification of the legal basis and the mechanism
   (inter-operator contracts, a sharing agreement). The retention periods follow from the basis;
   choosing periods first is backwards, and this codebase has done it in that order.

   **Now the sharpest open question in the project.** Article 192 of the Code offers exactly two
   bases — the subject's consent, or a legal obligation binding the controller. There is no
   legitimate-interest basis, which is the one a credit reference agency in Europe relies on. TIX
   therefore needs consent obtained through the operators' subscriber contracts, with all the
   fragility article 196 attaches to that, or an instrument obliging operators to report. The second
   does not exist yet. See `TIX_LEGAL_BASIS.md` §1.
4. **What a subject can demand.** Built in V21, as a case: somebody comes forward, a member of
   staff records how their identity was checked, and a decision is made with written grounds.
   Access returns their whole file across every operator. Dispute and rectification suppress the
   affected records immediately, before the case is decided, because the harm of being wrongly
   listed accrues daily.

   **Erasure is granted for settled records and refused for outstanding ones**, and that rule is a
   decision rather than a reading of the law. An unconditional erasure right would let anybody
   delete their own debts, so no operator would contribute and the right would defeat the registry
   it attaches to. Once a debt is regularised the operator has no remaining interest in reporting
   it. Whether the Code du numérique permits this balance is exactly the question in item 1, and it
   is still unanswered.
5. **The CDF threshold**, which is a related gap: the reporting threshold is configured for USD
   only and a currency with no configured floor is refused. Setting CDF means owning a USD/CDF
   rate. See `TixProperties`.

## Before this goes anywhere near real data

The numbers in `application.yml` must be reviewed by somebody qualified on DRC data protection
law, and the review recorded here. Reading the statute — which has now been done — is not that
review; it narrows the questions and does not answer them. Until counsel has looked at it, treat
every period in this system as an engineering default that happens to look like a legal one.
