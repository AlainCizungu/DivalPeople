# Demonstrating DIP / TIX

A path through the product that shows the loop end to end, with the exact values to type. Local
profile only; every business named here is invented and every figure is seeded.

## Before starting

**Three terminals, one command each.** Written out separately because pasting them as a block
sends the comments to Gradle as task names.

```
./infra/dev.sh up
```

```
cd backend && ./gradlew bootRun
```

```
cd frontend && npm run dev
```

Wait for `Started DipApplication` in the second one. If instead it says **"Port 8080 was already in
use"**, something else is answering as the API — an old container, or an orphaned run from
yesterday — and `dev.sh up` will now name it and tell you how to stop it. Worth knowing why this
matters: the backend exits, the squatter keeps serving, and every screen works while running code
of any age. It has cost this project an afternoon twice.

Seeding runs on first start and is skipped afterwards, so a restart against an existing database
changes nothing. To start clean, drop the database and let Flyway rebuild it.

**Sign-ins** (from `infra/keycloak/realm-dip.json`):

| User | What it is | Sees |
|---|---|---|
| `operator-a` | A telecom, declarant + inquirer + compliance officer | Everything except participants |
| `operator-b` | A second telecom — use this one for the Orange import | Its own book. **No compliance officer role**, which is the point of the separation-of-duties step in section 7 |
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
| Name | `Nouvelle Société Démo SARL` |
| Amount outstanding | `1500` USD |
| Service | `POSTPAID` |
| Date the obligation fell due | any date in the past |
| Dunning evidence | tick it |

Two things to show deliberately:

- **Untick dunning evidence.** The submit button disables and the form says *Required before you
  can declare* — the refusal happens in front of the operator rather than after a round trip. The
  server refuses it too, and says so in a sentence, but you will not see that by clicking: nothing
  is sent.
- **Enter `50` as the amount.** Refused: below the 100 USD reporting threshold. That floor is the
  whole proportionality argument for the scheme — a national bad-payer registry that accepts a
  two-dollar dispute is a punishment, not a credit instrument.

The response says whether this put somebody into the registry who was not in it before. That
distinction — adding to a file versus opening one — is worth pointing at.

### 3. Reporting by spreadsheet — `operator-a` → **Data imports**

Register a source, then upload a file. **Both real telecom exports work**, and they are worth
showing in that order because they are not alike:

| | Vodacom | Orange |
|---|---|---|
| Rows | 4,290 | 342 |
| `Balance` total | 12,383,011.42 | 9,645,928.64 |
| Header | row 4, under a blank row and a totals line | row 1 |
| Identifies customers by | `BPR_0`, its own account reference | nothing but the name |
| Write-offs | every row | 9 rows, against `ok` and blank |
| Unlabelled columns | none | three, two of them holding data |

Both figures above were checked against the files themselves. The counts of records created and
refused below are estimates from the same analysis and depend on the reporting threshold, so read
them off the screen rather than repeating them from here.

What to show:

- The batch carries a **SHA-256 of the bytes as received**, so an auditor holding the operator's
  copy can ask "is this the file you sent us".
- The rows are shown **exactly as stored** — nothing is mapped, no amount is parsed. Publishing
  accepts a delivery and stops there; a separate step turns rows into records, and the gap between
  the two is deliberate rather than unfinished.
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

- **Mark validated, publish, then create the records.** Deriving is a separate button from publishing on
  purpose: publishing accepts the delivery, deriving makes the people in it visible to every other
  operator. Tick the dunning confirmation — a typed declaration carries that assertion per record,
  so an import has to carry it too, and it is recorded against whoever clicked.

  Expect roughly 3,699 records created and 591 refused, listed by row number and reason: below the
  100 USD floor, or a credit balance. That is the threshold and decision 4 working, visible, on
  their own data. Then open **Exposure** — the provenance panel that has said zero imported records
  all session now says otherwise.

- Upload the same file twice. Refused, naming the batch that already holds it — **once the first
  one is published**. The check is against live batches, so re-sending a file that was uploaded and
  not yet published is not a duplicate of anything.

### 3b. The second operator's file, which is a different animal — `operator-b`

Sign in as `operator-b` and import the Orange export the same way. It is worth doing live, because
almost everything that made the Vodacom file easy is missing here and the platform has to say so
rather than cope silently.

- **Its header is on row 1** and three of its columns have no heading at all. One of those is empty
  from top to bottom and is dropped as padding; two hold real content — a write-off flag on nine
  rows and a note on one — and come back as `Column U` and `Column V`. A position is the only true
  thing available, so that is what they are named, and the profile shows what is in them.

  Worth saying: the platform refused this file outright until recently, for one missing heading.
  That was the parser being right about its own rule and useless about a real export.

- **It identifies nobody.** Its first column is a row number and its second is the customer name
  under a heading that reads `0`. No account number, no RCCM, no tax number, no usable phone
  number. So tick **This file has no identifier column** and map the name as the name.

  Read the note that appears. Identity then comes from the name, **inside Orange's book only**, and
  if two rows shared a name the whole delivery would be refused rather than recording two companies
  as one. All 342 names here are distinct, so it imports.

- **Then say the uncomfortable part out loud, with both books on the screen.** Vodacom's customers
  are identified by Vodacom's account numbers. Orange's are identified by their names. Nothing in
  either file says that a company in one is the company in the other, so the exchange cannot join
  them — and it does not pretend to.

  That is not a limitation to apologise for. It is the finding, produced from their own data in an
  afternoon, and it is exactly the conversation to have with both of them: **an exchange is worth
  joining only if its members bring a national identifier.** An RCCM or a tax number alongside what
  they already send is all it takes, and until then each operator gets a private book and no
  exchange at all.

*Also settled by comparing the two: whether a write-off is a status or a different kind of record.
Vodacom's file reads `Write off` on all 4,290 rows, which makes it look like a property of the
delivery. Orange's has nine, against `ok` and blank — so it is a per-account state one operator
tracks and the other does not, which is a status.*

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
when precisely one in the registry carries that name, and a *prefix* finds nothing — `Atlas` will
not match `Atlas Distribution SARL`, so the name box cannot be walked one letter at a time.

Two further refusals are real in the code and **have no seed data behind them**, so describe them
rather than trying to type them: two businesses sharing a name return "review required" instead of
a list, and a personal name never resolves on its own. The second matters — the profiled Vodacom
export had 48 names sitting on more than one account inside a single operator's book.

**The line to deliver on the second row:** operator A now knows another institution reports a debt
against this company, and does not know the amount, does not know which institution, and never
will. That is why a competitor would join.

> **Do not say "two institutions" while pointing at the card.** The number beside *Participating
> institutions holding a record* is currently a count of distinct statuses, not of institutions, so
> two operators both reporting an outstanding debt show as **1**. The count the sentence needs is
> not in the API at all. Known, recorded, and not yet fixed — say the sentence without the number,
> or fix it before the meeting.

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
- The provenance panel at the bottom says how many of these records came from an imported file
  rather than from the API. After the imports above it is most of them, and the point is that the
  screen answers the question at all — an operator asking "where did this come from" gets a number
  rather than a promise.

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

Worth thirty seconds: an authorisation boundary that has never been demonstrated is a claim.

Open **Inquiries**, **Exposure**, **Search**, **Subject requests** or **Audit trail** — each
refuses in a sentence naming the permission that is missing. **Data imports** and **Declare** also
refuse, but with the platform's generic wording, which names no permission. Show one of the first
five; the difference is a rough edge worth knowing about rather than discovering on stage.

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
- **Withdrawing a case** — the status exists and nothing sets it. A person who stops pursuing a
  case leaves it open until somebody decides it.
- **Matching a company across the two operators.** Not a defect and not a gap in the code: Vodacom
  identifies customers by its own account numbers and Orange by name alone, so there is nothing in
  either file that says a company in one is the company in the other. Section 3b is where to say
  this, and it is the most useful thing in the demo.
