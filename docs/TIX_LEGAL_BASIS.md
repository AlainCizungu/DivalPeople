# The Code du numérique, read against what TIX actually does

**Source.** Ordonnance-loi n° 23/010 du 13 mars 2023 portant Code du numérique, **Livre III,
Titre III — Des données personnelles (articles 183 à 261)** and **Titre IV — De l'Autorité de
protection des données (articles 262 et suivants)**. Read 8 August 2026 from the consolidated text
published by droitnumerique.cd.

**This is a reading of the statute by an engineer, not legal advice.** It exists so that the build
stops resting on assumptions nobody had checked, and so that Congolese counsel can be asked precise
questions instead of general ones. Two caveats on the source itself: the text used is a private
consolidation rather than the *Journal Officiel*, and no implementing measures, APD guidelines or
sector instructions have been searched for. Both should be confirmed before anything here is relied
on.

---

## 1. The finding that matters most: there is no legitimate-interest basis

> **Article 192.** « Le traitement des données personnelles n'est licite que dans la mesure où la
> personne concernée a consenti au traitement de ses données à caractère personnel **ou** si le
> traitement est nécessaire à l'exécution d'une obligation légale à laquelle le responsable du
> traitement est soumis. »

Two lawful bases. Consent, or a legal obligation binding the controller. **That is the whole list.**

There is no equivalent of GDPR article 6(1)(f). This is not a detail — a credit reference agency in
Europe exists on legitimate interest, because the one thing you cannot realistically obtain from a
defaulting debtor is their enthusiastic consent to being listed. That route is not available here.

So TIX needs one of:

- **Consent**, obtained from the subscriber — almost certainly in the operator's contract. But
  article 196 says consent must be demonstrable, as easy to withdraw as to give, and that in
  assessing whether consent was freely given « il y a lieu de tenir le plus grand compte de la
  question de savoir […] si l'exécution d'un contrat […] est subordonnée au consentement au
  traitement de données à caractère personnel qui n'est pas nécessaire à l'exécution dudit
  contrat ». Making a telephone line conditional on consenting to registry sharing is exactly the
  arrangement that sentence is aimed at. A consent that can be withdrawn at any time is also a
  fragile foundation for a registry: withdrawal removes the basis, and article 215(5) then obliges
  erasure.
- **A legal obligation** — an instruction or regulation from the ARPTC, the Banque Centrale du
  Congo, or a dedicated instrument obliging operators to report defaults to a designated exchange.
  This is the basis credit registries elsewhere in the region rest on, it is durable in a way
  consent is not, and **it does not exist yet.**

**Consequence for the project.** The single most valuable thing the feasibility study can deliver
is not a schema. It is an answer to: *under which of these two bases does the exchange operate, and
if the second, what instrument creates the obligation?* Everything in section 4 below follows from
that answer, and the schema does not.

## 2. What is unambiguously in scope

Article 183 lists the categories that constitute personal data. Point 4 is:

> « des données de facturation et de paiement : montant et historique des factures, état de
> paiement, relances, soldes de paiement, date de prélèvement »

That is a description of `tix_debt_record` written into the statute. Amount, payment status, dunning
history, balances. There is no argument to be had about scope.

Point 6 covers « des données sur des personnes morales […] faisant apparaître les données
personnelles ». **The business credit check is not outside the regime.** An RCCM record that
identifies a sole trader or names a director carries personal data, and the exchange's business
orientation buys no exemption. Article 185's exclusions (household activity, technical caching,
criminal-enforcement authorities) do not apply.

## 3. TIX needs prior *authorisation*, not merely a declaration

Article 186 subjects all processing to a **prior declaration** to the Autorité de protection des
données, which issues a *récépissé*; processing may begin on receipt.

Article 187 goes further, listing processing that requires **prior authorisation**:

> « 3. le traitement portant sur un numéro national d'identification ou tout autre identifiant de
> même nature, **notamment les numéros de téléphone** »

`IdentifierType` contains `NATIONAL_ID` and `MSISDN`. Both are named. TIX is squarely in the
authorisation regime.

Article 190 sets the clock: the APD decides within **30 days**, extendable once by 30 on a reasoned
decision; **silence is acceptance**; a refusal may be challenged by *recours gracieux* within 15
days. Article 188 lists what the application must contain — purposes, interconnections, categories
of data and of subjects, who has access, recipients, the department handling access requests,
security measures, use of processors, envisaged third-country transfers.

**Consequence.** Most of article 188's content already exists in this repository, scattered across
`AGENTS.md`, the ADRs, and the module documents. Assembling it into the application is a writing
task, not a research one, and it should be done while the design is fresh.

## 4. Retention: the law sets no number, and that is the answer

> **Article 193(3).** Data must be « conservées sous une forme permettant l'identification des
> personnes concernées pendant une durée n'excédant pas celle nécessaire à la réalisation des
> finalités pour lesquelles elles sont collectées ».

**There is no statutory period.** Nothing in the Code says three years, or five, or any other
figure. Retention is bounded only by necessity relative to purpose.

This resolves the open question in `TIX_RETENTION.md` in an uncomfortable way. The 3/5-year figures
are not unlawful — but they are also not *supported*, and no amount of searching will find a number
to point at. What the law requires instead is a **documented justification**: why three years is
necessary for the purpose, and why not two. A configuration comment saying "the TDR's illustrative
figures" is not that justification, and the burden of producing one sits with the controller.

Article 216 adds an ongoing duty: the controller must « examine[r] périodiquement la nécessité de
conserver ces données ». The nightly purge satisfies the mechanical half. The periodic *review* of
whether the periods themselves remain justified is a governance process that does not exist.

Note also that under article 192 the retention question partly collapses into the basis question: if
the basis is consent, article 215(5) obliges erasure on withdrawal regardless of any period the
platform has configured. **A registry running on consent cannot guarantee a three-year record.**

## 5. Statutory deadlines the platform does not currently track

| Right | Deadline | Article |
|---|---|---|
> **Revised August 2026.** These were 60 days for access and 30 for everything else, read off the
> articles below. Counsel advised 10 and 20 and those are what the platform now applies. Being
> wrong in the earlier direction was the expensive kind: a case answered on day forty under the old
> figures was already a month late. A request keeps the deadline it was given when it was raised,
> so the change binds new cases rather than retroactively making an existing queue overdue.

| Access — a copy of the information | **10 days** from receipt | 210, and counsel, Aug 2026 |
| Rectification / blocking — communicate what was done | **20 days** | 214, and counsel, Aug 2026 |
| Erasure | **20 days** | 215, and counsel, Aug 2026 |
| Opposition — communicate what was done | **20 days** | 213, and counsel, Aug 2026 |
| Breach — notify the APD **and the data subject** | « sans délai » | 244 |

`SubjectRequest` records when a case was raised, verified and decided. It has **no due date, no
overdue state, and nothing that surfaces a case approaching its deadline.** Article 214 further
provides that missing the deadline is itself grounds for the subject to complain to the APD. This is
a concrete, small gap with a clear fix.

**Article 214 also contains an obligation the platform cannot currently discharge:**

> « le responsable du traitement communique les rectifications ou effacements des données effectués
> à la personne concernée elle-même **ainsi qu'aux personnes à qui les données inexactes […] ont été
> communiquées** »

When a dispute is upheld, every institution that was told about that subject must be told the record
was wrong. DIP audits each inquiry with the subject, the enquiring tenant and the stated purpose, so
the recipient list is derivable — but nothing derives it, and no notification is sent. **Today a
wrongful listing is corrected at the source and left standing in the memory of everyone who read
it.** That is the most serious functional gap this reading found.

## 6. Data localisation

> **Article 201.** « Les données personnelles sont stockées et/ou hébergées en République
> Démocratique du Congo. »

Hosting abroad requires the APD to find the destination adequate **and** prior authorisation, with
ongoing supervision. Article 202 lists narrow exceptions for transfers to inadequate destinations;
none of them describes routine hosting.

**Consequence.** This is an architecture constraint, not a paragraph. It reaches the production
database, the object store, the encrypted backups described in the deployment runbook, the container
registry, and any managed service. A deployment on a foreign cloud region is not a detail to settle
later — it is either lawful with an authorisation or it is not lawful.

## 7. The exchange itself: articles 197–200

Article 197 permits transmission between controllers. Article 198 then conditions it:

- transmission to another controller happens « **avec le consentement de la personne concernée** »;
- the transmitting controller must verify the identity and standing of the recipient;
- the recipient « est tenu de les utiliser que pour de raisons pour lesquelles elles lui ont été
  communiquées »;
- « **un accord de confidentialité est conclu entre les deux responsables de traitement** ».

Three of those four are already in the design. Purpose limitation on the recipient is why every
inquiry carries a stated purpose; identity of the recipient is the authenticated tenant. The
confidentiality agreement between participants is a governance artefact that must exist on paper.

The first is the problem again: **consent of the data subject, for the transmission itself.** Same
question as section 1, arriving from a different direction.

**One tension worth putting to counsel.** Article 200 requires that a communication of personal data
carry « l'identité du responsable qui a transmis les données ». TIX deliberately does *not* tell an
enquiring operator which institution reported a debt — a protection for participants and, indirectly,
for subjects. Whether DIP is itself the transmitting controller (in which case naming DIP satisfies
the article) or a conduit between operators (in which case it may not) is a question with a real
answer and a real product consequence.

Article 254 governs « l'interconnexion des fichiers de données personnelles »: it must serve legal or
statutory objectives presenting a legitimate interest for the controllers, must not cause
discrimination or reduce rights, and must respect relevance. Note where "legitimate interest"
appears in this Code — as a condition on interconnection, **not** as a basis for lawfulness.

## 8. Joint controllers, and who answers the subject

Articles 221 and 253: where two or more controllers jointly determine purposes and means, they are
joint controllers, must define their respective obligations by agreement — particularly regarding
the exercise of subjects' rights — and may designate a contact point. **The outline of that
agreement must be made available to the data subject** (article 253 al. 3).

Article 252 makes them **jointly and severally liable** for damage, each answerable for the whole so
that the subject obtains effective compensation.

**Consequence.** The participation agreement is not optional paperwork, its outline is public-facing,
and an operator joining TIX is accepting liability for the exchange's failures as well as its own.
That is a commercial fact to put in front of participants early rather than late. It also argues for
DIP being the designated single contact point, which is what the subject-rights module already
assumes.

## 9. The risk score, before it is built

Article 245 requires a **data protection impact assessment before processing** where there is:

> « l'évaluation systématique et approfondie d'aspects personnels concernant des personnes physiques,
> qui est fondée sur un traitement automatisé, y compris le profilage, et sur la base de laquelle
> sont prises des décisions produisant des effets juridiques […] ou l'affectant de manière
> significative »

A credit score used to refuse a line or a loan is the textbook case. Article 246 adds that if the
assessment shows a high residual risk, the APD must be consulted before processing, and it has
**eight weeks, extendable by four**, to respond.

Articles 209(2) and 220(12) give the subject the right to be told of automated decision-making and
profiling, « des informations utiles concernant la logique sous-jacente, ainsi que l'importance et
les conséquences prévues ».

**Consequence.** The roadmap's instruction — *do not start with opaque ML; establish a transparent
baseline first* — is not a stylistic preference. It is what article 220(12) requires, and a DPIA plus
possible prior consultation is a twelve-week item that belongs on the plan before Phase 5, not
inside it.

## 10. Sanctions

Article 255 lists breaches including unfair collection, communicating personal data to an
unauthorised third party, and collecting a national identification number without meeting the legal
conditions. Article 256 allows a warning and a *mise en demeure* with a deadline of no more than
eight days. Article 257 then provides fines from **8,000,000 to 200,000,000 francs congolais** where
the breach had no grave impact, with far heavier consequences otherwise, and article 261 requires
sanctions to be **made public**.

No USD equivalent is given here. Converting requires a CDF rate, that rate moves, and this project
already refuses to invent one for the reporting threshold; the same reasoning applies to a figure
that would end up in a board paper.

---

## What this means for the build

Ordered by how much it changes.

1. **Establish the lawful basis before anything else.** Consent or legal obligation, article 192.
   This is a question for AJF and counsel, not for the codebase, and every item below depends on it.
2. **Notify prior recipients when a record is corrected or erased** (article 214). The audit trail
   holds the recipient list; nothing reads it. This is the largest functional gap found.
3. **Put deadlines on subject requests** — 10 days for access, 20 for the rest — with an overdue
   state visible to whoever is handling the queue.
4. **Decide where this is hosted** (article 201), including backups, before the pilot rather than
   after.
5. **Write the article 188 application** for prior authorisation. Most of the content already exists
   in this repository.
6. **Justify the retention periods in writing**, since the law provides no number to hide behind, and
   schedule the periodic review article 216 requires.
7. **Designate a DPO.** Article 189(4) exempts a controller who has designated one from the prior
   declaration formality — though not from authorisation under article 187, and not where a
   third-country transfer is envisaged.
8. **Plan the DPIA before Phase 5**, with eight to twelve weeks allowed for possible prior
   consultation.
9. **Breach notification runs to the data subject as well as the APD** (article 244), with no
   threshold. The incident response process in the runbook assumes the regulator only.

## What did not change

The reading found no conflict with the parts of the design that were most argued over:

- **Erasure as deletion rather than concealment** is what article 215 describes, and article 216
  requires mechanisms for it.
- **Suppressing a disputed record before the case is decided** is more protective than article 214
  requires, and article 194's duty to erase or rectify inaccurate data supports it.
- **The audit trail** is not merely good practice: article 219(14) requires that the identity of
  anyone who accessed the system, what they read or changed, and when, be establishable after the
  fact.
- **Purpose capture on every inquiry** is article 198's purpose limitation, implemented.
- **Refusing to create a subject when somebody asks whether they are listed** aligns with article
  193's data minimisation and article 243's privacy by default.
- **Encrypted backups** are required by articles 219(15) and 221 — and article 244(1) makes
  encryption the condition that can excuse notifying subjects of a breach.

## The question this leaves open

The registry's rules were written to be defensible on their own terms: a reporting threshold so a
small debt cannot make somebody unbankable, retention that runs from the default date, erasure of
settled records and refusal for outstanding ones. Read against article 192, the last of those is the
one that wobbles — refusing an erasure request needs a basis for continuing to hold the data, and
"the operator would object" is not one of the two the Code offers.

If the basis turns out to be consent, a subject who withdraws it is entitled to erasure under
article 215(5) whether or not the debt is settled, and the exchange has no answer. **The design
survives on the legal-obligation basis and is difficult to defend on the consent basis.** That is
the sharpest reason to pursue the former, and it should be said plainly to AJF.
