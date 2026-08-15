# The import template, and what publishing one changes

`docs/MODELE_IMPORT_DIP.xlsx` — the workbook DIP sends to AJF, which AJF takes to the operators.
Written in French because that is the language of everyone who will fill it in.

Counsel asked for it directly:

> *"c'est au concepteur de l'application d'envoyer un modèle de tableau qui contient tous les
> éléments et rubriques à compléter colonne par colonne"*

Both telecoms have agreed to adapt their exports to it. This document is the reasoning; the
workbook is the deliverable.

---

## This inverts the problem the platform has been solving

Everything built for imports so far exists because files arrive in whatever shape a billing system
produces. The profiler reads a workbook whose header is on row four under an unlabelled total. The
mapping is defined by the operator, on screen, per delivery. Identity can be taken from a name
because one real delivery carries no identifier at all. Account references are scoped to the
operator that issued them because two operators number their customers from one upwards.

None of that becomes useless — a template is a request, and deliveries will still arrive imperfect.
But it changes what the *next* file can carry, and that is the highest-leverage thing available
right now, because it has a lead time and an external dependency: the sooner AJF has it, the sooner
a delivery arrives with the fields the platform cannot currently ask for.

## The two columns that are the point

`Secteur d'activité` and `Adresse d'exploitation`. Counsel's answer on matching names them, and the
resolution screen has been reporting them as *never available* since it was built — his answer and
the screen's own admission are the same finding reached from two directions.

They matter because of what the register number turned out to be. An RCCM is reissued when a company
amends its statutes or adds capital, so two different numbers may be two companies or one company
either side of a change. The scorer used to resolve that ambiguity by always guessing "two", which
meant a re-registered company could never be recognised as itself; it now treats a conflicting RCCM
as advisory and puts the pair in front of a person. That is more honest and it is not more
*informative*. Sector and address are what would actually separate the two cases.

## Six columns are asked for and not yet used, and the workbook says so

`Statut` on the Notice sheet reads **Utilisée** or **Bientôt**, and *Bientôt* says plainly that the
column is not yet exploited and its contents are not retained. Asking for a field and silently
dropping it would be worse than not asking: the operator does the work, the data does not arrive,
and nobody finds out until somebody wonders why the match confidence never improved.

Asking now anyway is the right trade. An operator changes an export once; being asked twice is how a
willing counterparty stops being willing.

## Three decisions inside the workbook worth defending

**No aging-bucket columns.** The current Vodacom export carries nine — `30 days` through
`360 + days` — and there is an open question about whether the `360 +` line rather than `Balance` is
the figure to report. The template asks instead for the amount due and **the date the obligation
fell due**, and DIP computes the aging itself. The question stops existing rather than being
answered.

**Dates are text, in `AAAA-MM-JJ`.** A date typed into a date-formatted cell reaches a reader as an
Excel serial number, and resolving the display format is precisely where a hand-written reader goes
quietly wrong. Every column except the amount is formatted as text, which also keeps a telephone
number from arriving as `2.4381e+11` and an account reference from losing a leading zero.

**The header is on row 1 and the caution is a cell comment.** The warning about deleting the example
rows would, as a cell, be a one-value row in the data area — so an operator who deleted the examples
and left the warning would hand DIP exactly the malformed file the warning existed to prevent. It
lives in a comment on `A1` and on the Notice sheet instead.

## The third sheet is the refusals

`Contrôles` lists what DIP rejects and whether the rejection costs a row or the whole file: below
the 100 USD threshold, a negative amount, no dunning, no as-at date, an unconfigured currency, two
rows sharing a name with no identifier, more than one sheet in the workbook.

Published rather than discovered. An operator who learns the rules from a rejection message learns
them one at a time, after doing the work.
