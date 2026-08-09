# Vodacom write-off export — field profile

Profiled 8 August 2026 from `VODA_Write_off_12M.xlsx`, 4,290 data rows, one sheet.

**No customer values appear in this document.** Field names, shapes, counts and fill rates are
recorded; names, account references and individual balances are not. The file itself stays out of
this repository.

## Shape of the file

| Row | Contents |
|---|---|
| 1 | blank |
| 2 | totals, unlabelled — 12,383,011.42 in the balance column |
| 3 | blank |
| 4 | **header** |
| 5+ | data |

The parser we had rejected this outright: `CsvReader` expected the header on row 1, and this is
XLSX rather than CSV. Both were the file telling us something true about how telecom exports
arrive.

**Both are now handled.** `XlsxReader` reads the workbook without a new dependency, and
`TabularReader` finds the header past the preamble — skipping a leading row only when it holds
fewer than two values and the file elsewhere is wider than that, which admits blank rows and an
unlabelled total and nothing else. Two limits are worth knowing: a workbook with more than one
sheet is refused rather than guessed at, and **a date cell arrives as an Excel serial number**
because resolving the display format is where a hand-written reader goes quietly wrong. This file
has no dates, so nothing is lost today.

## Columns

| Column | Filled | Distinct | Notes |
|---|---:|---:|---|
| `Bsr` | 4290 | 4032 | short business name / trading name, 2–44 chars |
| `Status A` | 4290 | 1 | always `Write off` |
| `BPR_0` | 4290 | **4290** | account reference — `V` + digits (4125) or plain digits (164), one odd value |
| `BPCNAM_0` | 4290 | 4238 | customer name, **truncated at ~35 characters** |
| `Balance` | 4290 | 3371 | numeric, 3 negative |
| `Not Due` | 25 | 12 | `-` used for zero throughout |
| `30 days` | 12 | 12 | |
| `60 days` | 1 | 1 | |
| `90 days` | 11 | 11 | |
| `120 days` | 10 | 8 | |
| `150 days` | 9 | 7 | |
| `180 days` | 30 | 25 | |
| `270 days` | 232 | 21 | |
| `360 + days` | 4262 | 3349 | where essentially all of the money is |
| `Status B` | 4290 | 2 | `Inactive` 3700, `Active` 590 |
| `PWC` | 4290 | 305 | repeats `BPR_0` for 304 rows, `#N/A` for the rest |
| `Write off Mars 2025` | 4290 | 4290 | **identical to `BPR_0` on every row** |
| `Descoped` | 4290 | 20 | `#N/A` on 4268 rows |
| `Vaccounts` | 4290 | 1 | always `1` |

## What the data says

**The aging reconciles exactly.** The nine buckets sum to `Balance` on all 4,290 rows, to the
cent, with no exceptions. That is unusually clean and means the aging model can be trusted rather
than merely used.

**`BPR_0` is a real business identifier.** Unique across every row, stable in shape. This is what
entity resolution should key on, and it is far stronger than the name.

**Names are truncated and collide.** `BPCNAM_0` is cut at about 35 characters, so
"BANQUE INTERNATIONALE DE CRE…" is what the file contains rather than a full legal name. 48 names
appear on more than one account. Any matcher relying on names alone would be wrong constantly.

**`PWC`, `Write off Mars 2025` and `Descoped` are spreadsheet working columns.** One duplicates
`BPR_0` exactly; the others are mostly `#N/A`, the residue of lookups. They carry no information
the other columns do not.

## What is not in the file, and matters

**There are no dates. None.** Not a default date, not an invoice date, not a write-off date. The
only temporal information is the aging bucket and the file's own name.

This is the finding that costs us something. `tix_debt_record.default_date` is NOT NULL, the
retention clock runs from it, and declaration refuses a future one — all built on the assumption
that an operator knows when the obligation fell due. This file says they do not export it. Deriving
a date from "360 + days" gives a lower bound and nothing more, and a retention period computed from
a guessed date is a guessed retention period.

**These are write-offs, not outstanding receivables.** `Status A` is `Write off` on every row: debts
Vodacom has already given up on. `DebtStatus` has no vocabulary for that. A written-off debt is a
different assertion from an unpaid one — arguably a stronger signal for an exchange, and certainly
not the same thing.

**588 rows — 13.7% — are below the 100 USD reporting threshold**, and three balances are negative
(credits). Our declaration path would refuse all 591. That is the threshold working as designed,
and it is worth seeing the number before anyone is surprised by it.

**Public institutions and licensed banks appear among the largest balances.** This is a commercial
and political fact rather than a technical one, and it belongs in the governance conversation
before it belongs in a schema.

## Decisions this forces

1. **Where does a default date come from?** ~~Derived from the aging bucket, taken from the file's
   reporting period, or does the schema stop requiring one?~~ **Answered by asking, 9 August 2026.**

   None of the three. The operator supplies the date the delivery is as at, on upload, and the
   derived default date is computed from it — deriving from the aging bucket would have given
   4,262 of 4,290 rows the same date and a retention expiry clustered on one day, and a guessed
   date is a guessed retention period.

   `tix_debt_record.default_date_source` records `REPORTED` or `DERIVED`, because the expensive
   mistake is not approximating a date, it is forgetting that it was approximated. Raw rows are
   immutable and every derived record names the row it came from, so when Vodacom sends real dates
   the correction is a re-derivation rather than a hunt.

   **A correction may only ever shorten retention, never extend it.** If a real date turns out to
   be later than the assumed one, the earlier expiry stands — otherwise fixing our own
   approximation would lengthen how long somebody is listed.

   The better outcome is still available and costs one email: **ask Vodacom to add a date column
   to the export.** That removes the approximation entirely.
2. **Is a write-off a status, or a different kind of record?**
3. **Does the 100 USD threshold stand** when it excludes a seventh of the first real dataset?
4. **What happens to credit balances** — refuse, ignore, or record as evidence the account is
   settled?
5. ~~**XLSX support**, and header detection that survives a preamble.~~ Built, 8 August 2026.
   The file can now be uploaded, stored and inspected; what it still cannot do is become debt
   records, which is decisions 1–4.
