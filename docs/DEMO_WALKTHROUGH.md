# Demonstrating DIP / TIX

A path through the product that shows the loop end to end, with the exact values to type. Local
profile only; every business named here is invented and every figure is seeded.

## Before starting

```
infra/dev.sh up            # Postgres, Redis, Keycloak
cd backend  && ./gradlew bootRun
cd frontend && npm run dev
```

Seeding runs on first start and is skipped afterwards, so a restart against an existing database
changes nothing. To start clean, drop the database and let Flyway rebuild it.

**Sign-ins** (from `infra/keycloak/realm-dip.json`):

| User | What it is | Sees |
|---|---|---|
| `operator-a` | A telecom, declarant + inquirer + compliance officer | Everything except participants |
| `operator-b` | A second telecom | The same, over its own book |
| `platform-admin` | Runs the network, no tenant of its own | Participants only |
| `no-roles` | A signed-in account with nothing granted | The refusals |

## The loop, in order

### 1. Who is on the network — `platform-admin` → **Participants**

Eight organisations across five sectors: two telecoms, two banks, an enterprise utility, a
government body, an NGO microfinance, and one telecom **suspended** so the status column shows more
than one state.

The point to make: DIP is not a telecom product. TIX is one edition of six, and the participant
list is the network the exchange is worth having.

*If asked why the banks have no data: because no bank has joined. The screen shows who is
registered, not who is contributing, and conflating those would be the first dishonest number on
it.*

### 2. Reporting a default by hand — `operator-a` → **Declare**

Use a business that is not already seeded, so it is visibly created in front of the audience:

| Field | Value |
|---|---|
| Identifier type | RCCM |
| Identifier | `CD/KIN/RCCM/22-B-8800` |
| Legal name | `Nouvelle Société Démo SARL` |
| Amount | `1500` USD |
| Service | `POSTPAID` |
| Default date | any date in the past |
| Dunning evidence | tick it |

Two things to show deliberately:

- **Untick dunning evidence** and submit. It is refused, in a sentence. A default cannot be
  reported without evidence that the contractual chase happened first.
- **Enter `50` as the amount.** Refused: below the 100 USD reporting threshold. That floor is the
  whole proportionality argument for the scheme — a national bad-payer registry that accepts a
  two-dollar dispute is a punishment, not a credit instrument.

The response says whether this put somebody into the registry who was not in it before. That
distinction — adding to a file versus opening one — is worth pointing at.

### 3. Reporting by spreadsheet — `operator-a` → **Data imports**

Register a source, then upload a file. The real Vodacom export works: XLSX, header on row 4 under a
blank row and an unlabelled total, 4,290 rows.

What to show:

- The batch carries a **SHA-256 of the bytes as received**, so an auditor holding the operator's
  copy can ask "is this the file you sent us".
- The rows are shown **exactly as stored** — nothing is mapped, no amount is parsed. Point at this
  rather than around it: publishing the batch does not create exposures yet, and the exposure
  screen says so out loud.
- **Press "Profile the delivery".** This is the moment worth pausing on with a telecom in the room.
  The hand analysis in `TIX_SOURCE_PROFILE.md` took an afternoon; the screen does it on their file
  while they watch. Expect it to say what that document says: `BPR_0` unique on every row and
  flagged as identifier-shaped, `Status A` a constant reading *Write off*, `Vaccounts` always `1`,
  the aging columns almost entirely empty, and `Balance` totalled exactly.

  Say plainly that **nothing here decides what a column means.** Which one is an amount and which
  is an identifier is still open; how many cells are filled is not, and that is the whole reason
  this could be built before those decisions.
- Below the profile, **rows that cannot become records**. Empty rows, exact duplicates naming the
  row they duplicate, and rows with a gap in a column that is unique everywhere else — a candidate
  identifier with a hole in it. These hold whatever the mapping eventually says, which is why they
  could be reported before it exists. Nothing is rejected: every row is stored as delivered.
- **Say what the columns mean.** Under the profile, a mapping: which column is the identifier,
  which is the name, which is the amount, with the profile's findings restated beside the form —
  the unique columns and the numeric ones. Point out that DIP is not choosing. Ten columns in that
  file are numeric and only Vodacom knows which is the balance.

  Saving does not edit a mapping, it supersedes it. A delivery already derived keeps the rules it
  was derived under, and the earlier versions stay listed.

- **Choose `Account number (this operator)` as the identifier type, and read the note under it.**
  `BPR_0` holds values like `V0172109`. That is Vodacom's own customer reference, not an RCCM and
  not any document a registry issued, and the type exists because until it did the only way to
  import this file was to declare those numbers to be business registrations.

  The consequence is worth saying to a telecom rather than hiding: operators number their
  customers from one upwards, so account 100234 exists at every one of them and means a different
  company at each. An account reference therefore identifies a subject **inside one operator and
  nowhere else**, and records imported with nothing but one are visible to their own operator and
  invisible to the exchange. Another operator asking about the same company finds nothing, and is
  right to — nothing in the file says who that company is in any national register.

  That is the argument for the pilot, made with their own data: the exchange is worth joining only
  if the members bring an RCCM or a tax number alongside their account numbers. The product shows
  the gap instead of covering it with a match nobody could defend.

- **Validate, publish, then create the records.** Deriving is a separate button from publishing on
  purpose: publishing accepts the delivery, deriving makes the people in it visible to every other
  operator. Tick the dunning confirmation — a typed declaration carries that assertion per record,
  so an import has to carry it too, and it is recorded against whoever clicked.

  Expect roughly 3,699 records created and 591 refused, listed by row number and reason: below the
  100 USD floor, or a credit balance. That is the threshold and decision 4 working, visible, on
  their own data. Then open **Exposure** — the provenance panel that has said zero imported records
  all session now says otherwise.

- Upload the same file twice. Refused, naming the batch that already holds it.

*Timing: the console logs `Received N rows from … in X ms`. If that number is large, say so and
move on — it is measured rather than hidden.*

### 4. Finding one you already know about — `operator-a` → **Search**

Type `grand horizon`. It finds the business in *your own book* and opens a profile: identifiers as
the exchange stores them, every record you hold aged from the date it fell due, retention date, and
whether it came from a file or the API.

Then search a business only operator B has reported. **Nothing.** That is the screen working, and
the empty state says so: finding nothing here means your own organisation has not reported them,
and nothing more. The exchange answers the other question.

Worth saying out loud if a telecom asks why it does not search everything: a subject is shared, so
a search over subjects is a search over the national registry — and no operator joins an exchange
where a rival can list its defaulters.

### 5. Checking a business before extending credit — `operator-a` → **Inquiries**

| Look up | Expect |
|---|---|
| RCCM `CD/KIN/RCCM/15-B-6604` | **Outstanding debt**, 1 institution reporting — a business only *operator B* knows about |
| RCCM `CD/KIN/RCCM/16-B-5150` | **Outstanding debt**, 2 institutions — owed at both operators |
| RCCM `CD/UVI/RCCM/20-B-1199` | **No adverse record** — this one is under dispute and therefore withheld |
| RCCM `CD/KIN/RCCM/99-B-0000` | **No match** |

A purpose is required before the button enables. Every inquiry is recorded with it.

**Leave the identifier blank and type the exact registered name instead.** It resolves a business
when precisely one in the registry carries that name. Three refusals are worth demonstrating: a
*prefix* finds nothing (`Atlas` will not match `Atlas Distribution SARL`), two businesses sharing a
name return "review required" rather than a list, and a *personal* name never resolves on its own —
the profiled export had 48 names on more than one account inside a single operator's book.

**The line to deliver on the second row:** operator A now knows two institutions report a debt
against this company, and does not know the amount, does not know which institutions, and never
will. That is why a competitor would join.

**And on the third:** the business is disputing that record, so it stopped being reported the day
the dispute was raised — before anybody decided who is right. The harm of being wrongly listed
accrues daily.

The illustrative score panel below the result is marked as a mock in heavy amber. Say plainly that
there is no risk model yet and that the panel is what one would look like.

### 6. The operator's own book — `operator-a` → **Exposure**

Real records, aged from the date each obligation fell due.

- **Two currencies, never added together.** USD and CDF sit on separate rows. Adding 500 USD to
  500 CDF produces a number that is wrong and looks entirely normal on a dashboard.
- **The aging chart** has something in every band, so the distribution has a shape rather than one
  bar. The oldest band is red because that is where the money is in a real book, and it is the one
  a credit committee looks at first.
- **Contested is a separate column** from outstanding — still money the operator is owed, but
  somebody is arguing about it.
- **Awaiting erasure should read zero.** If it does not, the nightly purge has stopped, and nothing
  else in the product would say so.
- The provenance panel at the bottom says how many of these records came from an imported file.
  It says **zero**, and it is on the screen rather than in a document nobody opens.

### 7. Somebody comes forward — `operator-a` → **Subject requests**

The part of the product nobody else in this market has, and the one worth slowing down for.

Open a case against a business that is in the registry — `CD/KIN/RCCM/15-B-6604` will do — as a
**Dispute**, with something in their own words. Then:

- **Look at the queue.** Every case carries a deadline: sixty days for access under article 210 of
  the Code du numérique, thirty for everything else. Overdue cases are marked, and missing the
  deadline is itself grounds for a complaint to the Autorité de protection des données.
- **Note what the queue does not show.** No name, no identifier. Whoever is handling a case already
  knows who walked in; a queue echoing identity documents back would be a second copy of the
  registry with weaker controls around it.
- **Verify their identity.** The evidence box is free text and mandatory — "National ID seen in
  person, photograph matches" is something a regulator can assess and a ticked box is not.
- **Uphold it.** The records stay suppressed, and every institution that was previously told about
  this subject gets a notification that the answer they were given is superseded. That is article
  214, and it is the difference between correcting a database and correcting a decision.

Then raise an **Access** request and disclose the file. It names the operators — which is exactly
what an enquiring operator is never told. The subject is entitled to know who is reporting them; a
competitor is not.

*Sign in as `operator-b` to show the separation of duties: it can open a case and cannot decide
one. Whoever takes the request at the counter should not also rule on it.*

### 8. Proving the accountability claim — `operator-a` → **Audit trail**

Everything above has been writing rows here. The landing page tells institutions that every
inquiry is recorded with the purpose it was made for; this is where that stops being a claim.

- The **stated reason** column is the one to point at. It is what turns "somebody looked this
  company up" into something an auditor can ask about.
- **Refused attempts are here too.** A rate-limited sweep that left no trace would just be a
  slower invisible sweep, so the denials are kept — which is why the count on the third card is
  worth showing rather than hiding.
- Rows are appended and never edited. A correction is a further event.
- Sign in as `operator-b` to see its own trail and none of operator A's.

*Accounts appear as identifiers rather than names, and that is deliberate: the trail belongs to no
single part of the platform, so it does not depend on the part that knows who people are.*

### 9. The refusals — sign in as `no-roles`

Every TIX screen refuses, in a sentence explaining which permission is missing rather than an error
code. Worth thirty seconds: an authorisation boundary that has never been demonstrated is a claim.

## What to say when asked "is this live?"

It is not. The landing page's **Where this is today** section is the honest answer and is on the
public page rather than in a footnote — running, designed, and undecided, in three columns. The
undecided column is real: whether a written-off debt is a status or a different kind of record,
where a default date comes from when the source file has none, and whether the reporting threshold
survives contact with real data.

And the larger one, which belongs in any serious conversation: **the Code du numérique offers two
lawful bases and neither is legitimate interest.** See `TIX_LEGAL_BASIS.md`. Nobody should be
promised a launch date before that is settled.

## Things that will not work, so do not open them

- **A risk score** — not built. Only the clearly-marked mock.
- **Publishing an import to create records** — the batch publishes, and derives nothing.
- **Withdrawing a case** — the status exists and nothing sets it. A person who stops pursuing a
  case leaves it open until somebody decides it.
