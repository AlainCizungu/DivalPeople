> **Superseded, 8 August 2026.** Belongs to Dival People (HR), which is being withdrawn from
> this repository. The payroll backend and migration V17 are still present and still tested;
> the screens are gone. Kept because the constraint it records — that component configuration
> must be signed off by a qualified payroll practitioner before any real pay run — remains
> true for as long as the code exists.

# Payroll — what this module does, and what it deliberately does not

Read this before configuring a payroll run, and before extending the module.

## What it does

Payroll **preparation**. It gathers what a pay run needs and produces a payslip that can be
reviewed, approved and exported:

- **Effective-dated compensation.** Salary history, never overwritten. A run for March uses the
  salary that was in force in March.
- **Pay components.** Earnings, deductions and employer contributions, each with a defined way of
  arriving at an amount: a fixed sum, a percentage of base, a percentage of gross, an hourly rate,
  or a figure entered by hand for that period.
- **Recurring assignments.** A component attached to a person, also effective-dated.
- **Periods and payslips.** A period is calculated, reviewed, approved and marked paid. Each
  transition is deliberate and recorded.
- **Inputs from the rest of the platform.** Unpaid leave days, unexplained absence and overtime
  minutes are carried onto the payslip from the leave and attendance modules, so the figures
  behind a deduction are visible on the document itself.

## What it does not do, and why

### It does not calculate statutory tax

There is no income tax table, no social security schedule and no set of thresholds anywhere in
this module. That is a deliberate refusal, not an unfinished feature.

Those rates are jurisdiction-specific, they change by decree, and they interact with allowances,
dependants and ceilings in ways that differ between countries and sometimes between provinces.
Getting them wrong means one of two things: underpaying people, or under-remitting to a revenue
authority. In most jurisdictions the second carries personal liability for a company officer.

Writing a plausible-looking DRC tax calculation from general knowledge would produce numbers that
are wrong in ways nobody notices until an audit, because a payslip that reconciles internally
looks correct whether or not the rate on it is the right one.

**What to do instead.** Configure statutory deductions as `PERCENT_OF_BASE` or
`PERCENT_OF_GROSS` components, with the rates taken from the current legal instrument and entered
by somebody qualified to read it. The module applies them in a defined order and shows its
working on every line. Verification that the rates are right is an accountant's job, and the
`basis` recorded on each payslip line exists so they can check it without re-running anything.

Before this module is used for a real pay run in any jurisdiction, the component configuration
must be signed off by a qualified payroll practitioner for that jurisdiction. That is a condition
of use, not a suggestion.

### It does not pay anybody

Marking a period `PAID` records that payment was made. It does not move money. Bank file
generation and any payment integration are separate work, deliberately not started, because a
system that can both calculate and disburse needs a segregation-of-duties design that does not
exist yet.

### It does not prorate a mid-period joiner or leaver automatically

Somebody who starts on the 15th gets a full month's base unless a manual adjustment is entered.
Proration rules vary — calendar days, working days, or a fixed fraction — and choosing one
silently would be a policy decision disguised as a default. This is the next thing to build here.

### It does not handle retrospective corrections

A payslip in an approved period is frozen. Correcting a past period means a compensating entry in
a later one, which is what most payroll practice does anyway — but the module has no explicit
support for it, and the arithmetic is currently the operator's to get right.

## The rules the code enforces

These are worth knowing because they constrain what an extension may do.

1. **Compensation is never updated in place.** A change closes the previous row and opens a new
   one. There is a partial unique index allowing only one open-ended salary per person.
2. **A payslip is a snapshot.** Employee number, name, base salary and every line's component code
   and name are copied at calculation. Renaming a component does not rewrite history.
3. **Totals are the sum of the lines.** Gross, deductions and net are never computed
   independently, and a database constraint requires `net = gross - deductions`. A payslip that
   does not reconcile cannot be stored.
4. **An approved period cannot be recalculated.** Reopening is an explicit act that returns it to
   draft and is audited.
5. **Nobody approves a payroll they are paid by.** The approver is recorded, and approving a
   period containing your own payslip is refused.
