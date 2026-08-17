# Build status

Moved off the landing page in August 2026. The page is a commercial page and should read as one;
this is the honest internal record, kept because a platform asking institutions to trust it with a
national dataset should be able to say precisely what exists.

Everything advertised on the landing page is in the first list. That is the rule: **the marketing
page may be confident, and it may not describe anything that is not here.**

## Running today

- Bilingual platform, sign-in, roles, and tenant isolation enforced by the database
- Declaring a default, with a reporting threshold below which nothing enters the registry
- Cross-operator inquiry returning status only, rate-limited and audited with a stated purpose
- How many institutions report a subject — never which, never how much, never since when
- CSV and Excel import with immutable raw storage and provenance for every row
- Turning imported rows into records, through a mapping the operator defines and versions
- Importing a delivery keyed on the operator's own account numbers, or on nothing but a name
- Taking a published delivery back, and taking back every record derived from it
- The DIP Risk Indicator, with every factor and weight published beside the figure
- Searching your own book by name or identifier
- Retention, expiry, and erasure that deletes rather than hides
- Subject access, dispute, withdrawal, and erasure requests, on statutory deadlines
- An operator exposure view, aged, per currency
- Entity resolution: candidate matches scored, shown side by side, and decided by a person — never merged automatically
- Consolidated profiles for businesses and individuals, listed together in one records screen with the kind as a filter
- Watchlists, swept as standing inquiries and charged like one
- Behavioural anomaly detection over the audit trail, including abuse of DIP itself
- Executive intelligence: your book today, thirteen months of activity, and how rights requests were answered
- Ask DIP: a question in words, answered with figures counted from rows
- A published import template, with every column explained, that operators prepare their exports against
- A permissions catalogue read from the guards themselves, and a settings screen that states where each value came from
- The Subject 360° profile: the indicator, your own exposure, whether anything is unpaid now and how often this company has settled with you before, how many institutions report, coded risk signals, and a timeline of your own records — one inquiry, one stated purpose

## Designed, not built

- Real-time APIs and scheduled feeds
- A language model that phrases answers as well as reading questions — configurable, off by default, and waiting on a legal answer about sending data abroad

## Not yet decided

- What happens when somebody withdraws consent while the debt is still unpaid. Counsel confirms the basis is contractual and that no law obliges an operator to report — which makes the current refusal to erase an outstanding debt harder to defend, not easier
- Whether the "+360 days" line in the operator files means the figure to report is that bucket rather than the balance. If it does, every amount already imported is wrong
- Whether debtor data may be sent to a language model hosted outside the DRC so that it can phrase an answer. The switch exists and is off
- Whether the amount owed should be disclosed to other institutions, as counsel has asked. Today the exchange discloses a count and a band, never a figure. **The named view is now built and switched off**: `dip.disclosure.name-institutions` prints the operators behind the count and `dip.disclosure.disclose-amounts` prints what each is owed. Neither may be turned on until counsel answers *and* the participants whose books become visible have agreed — an operator that joined on "never which, never how much" and finds its ledger itemised on a competitor's screen has a reason to leave and a reason to sue. Every answer carrying names writes a `TIX_CONTRIBUTORS_DISCLOSED` audit row

## The rule this file exists to enforce

Before adding a claim to the landing page, it has to appear in *Running today* — and before it
appears there, it has to be traceable to something in the tree. The last audit checked all
twenty-two against a file. When a claim moves from *Designed* to *Running*, it may move to the
landing page and not before.

