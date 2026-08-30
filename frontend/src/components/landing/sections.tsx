"use client";

import Image from "next/image";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { SocialRow } from "./social";
import { Pill, type Tone } from "@/components/ui";
import { Eyebrow, FeatureCard, SectionHeading, gradientFor } from "./primitives";

/**
 * The marketing sections, in page order.
 *
 * <p>Everything numeric on this page — risk scores, exposure figures, the portfolio table, the
 * assistant's answers — is invented, and every panel carrying one is labelled illustrative in
 * both languages. That labelling is content, not decoration: the whole point of the page is to
 * show institutions what a national risk registry would look like, and the failure mode is
 * somebody screenshotting a plausible score against a named company and circulating it as output.
 */

export function QuickLinks() {
  const messages = useMessages();
  return (
    <div className="border-y border-line bg-white">
      <div className="mx-auto flex max-w-7xl flex-wrap gap-x-8 gap-y-3 px-6 py-4 text-sm font-semibold text-ink">
        {/* Anchors, not words. This band sat directly under the hero naming five things the
            product does and doing nothing when clicked — the most valuable strip of pixels on the
            page spent on decoration. "Dival AI" pointing at nothing is what made the assistant
            look unadvertised while its section was two blocks down. */}
        {messages.landing.quick.map((item) => (
          <a key={item.href} href={item.href} className="transition hover:text-blue">
            {item.label}
          </a>
        ))}
      </div>
    </div>
  );
}

export function PlatformSection() {
  const messages = useMessages();
  const { platform } = messages.landing;
  return (
    <section id="platform" className="mx-auto max-w-7xl px-6 py-14 md:py-20">
      <SectionHeading eyebrow={platform.eyebrow} title={platform.title} body={platform.body} />
      <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-4">
        {platform.cards.map((card, index) => (
          <FeatureCard
            key={card.title}
            badge={card.badge}
            gradient={gradientFor(index)}
            title={card.title}
            body={card.body}
            more={card.more}
          />
        ))}
      </div>
    </section>
  );
}

/** The ingestion pipeline, as five numbered steps. */
export function ExchangeSection() {
  const messages = useMessages();
  const { exchange } = messages.landing;
  return (
    <section id="exchange" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading eyebrow={exchange.eyebrow} title={exchange.title} body={exchange.body} />
        <ol className="grid gap-6 md:grid-cols-3 xl:grid-cols-5">
          {exchange.steps.map((step, index) => (
            <li
              key={step.title}
              className="rounded-xl border border-line bg-white p-6 transition hover:-translate-y-1 hover:shadow-lg"
            >
              <div className="mb-3 text-3xl font-extrabold tabular-nums text-blue">
                {String(index + 1).padStart(2, "0")}
              </div>
              <h3 className="mb-2 text-xl font-bold text-navy">{step.title}</h3>
              <p className="text-sm text-muted">{step.body}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

export function RiskSection() {
  const messages = useMessages();
  const { risk, actions } = messages.landing;
  return (
    <section id="risk" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
      <div className="grid items-center gap-12 lg:grid-cols-[1fr_0.9fr]">
        <div>
          <SectionHeading eyebrow={risk.eyebrow} title={risk.title} body={risk.body} />
          <ul className="mb-7 flex flex-wrap gap-2.5">
            {risk.factors.map((factor) => (
              <li
                key={factor}
                className="rounded-full border border-line bg-white px-3.5 py-1.5 text-sm text-ink"
              >
                {factor}
              </li>
            ))}
          </ul>
          <a
            href="#demo"
            className="rounded bg-blue px-5 py-3.5 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {actions.viewSampleReport}
          </a>
        </div>

        <div className="overflow-hidden rounded-2xl border border-[#e6edf5] bg-white shadow-xl">
          <div className="flex items-center justify-between bg-navy px-5 py-4.5 text-white">
            <strong>{risk.panelTitle}</strong>
            <small className="text-[#9ec5e8]">{risk.panelTag}</small>
          </div>
          <div className="p-5">
            <dl className="mb-5 grid grid-cols-3 gap-3">
              {[
                [risk.scoreLabel, risk.scoreValue],
                [risk.bandLabel, risk.bandValue],
                [risk.modelLabel, risk.modelValue],
              ].map(([label, value]) => (
                <div key={label} className="rounded-lg border border-[#e8edf3] bg-[#f7f9fc] p-3">
                  <dt className="text-xs text-[#64748b]">{label}</dt>
                  <dd className="mt-1 text-lg font-bold text-navy">{value}</dd>
                </div>
              ))}
            </dl>

            {/* Ratings, not percentage bars, because ratings are what the product produces.
                What stood here showed "Total exposure — $184K" beside four invented bars, and
                exposure is the one figure the model refuses to compute: the currency of the
                amount column in both operator deliveries is unconfirmed. A marketing page
                promising a number the product declines to give is the stale copy a buyer
                notices. */}
            <p className="mb-2 text-xs font-bold uppercase tracking-wide text-[#64748b]">
              {risk.rowsTitle}
            </p>
            <table className="w-full text-sm">
              <tbody>
                {risk.rows.map((row) => (
                  <tr key={row.label} className="border-b border-line/60 last:border-0">
                    <td className="py-2 text-ink">{row.label}</td>
                    <td className="py-2 text-right">
                      <Pill tone={row.tone as Tone}>{row.rating}</Pill>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <p className="mt-4 rounded border border-line bg-soft px-3 py-2.5 text-xs text-muted">
              {risk.notAssessedNote}
            </p>
          </div>
        </div>
      </div>

      {/* DIP Credit Intelligence, as a design.
          Below the section rather than inside the two-column grid: the panel above is drawn from
          what the product does today and this is a picture of a module that does not exist yet.
          Putting them side by side would invite a reader to take them as the same kind of claim.

          The caption is not decoration. This section sits on a page whose capabilities block
          promises "everything below is running today, not a roadmap", so an unlabelled screenshot
          of an unbuilt module reads as a fourth thing that is running. */}
      <figure className="mx-auto mt-14 max-w-5xl">
        <div className="overflow-hidden rounded-2xl border border-[#e6edf5] bg-white shadow-xl">
          <Image
            src="/credit-intelligence.webp"
            alt={risk.previewCaption}
            width={1536}
            height={1024}
            className="h-auto w-full"
            sizes="(max-width: 1024px) 100vw, 1024px"
          />
        </div>
        <figcaption className="mt-3 text-center text-xs text-muted">
          {risk.previewCaption}
        </figcaption>
      </figure>
      </div>
    </section>
  );
}

export function EntitySection() {
  const messages = useMessages();
  const { entity } = messages.landing;
  return (
    <section className="bg-white">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading eyebrow={entity.eyebrow} title={entity.title} body={entity.body} />
        <div className="grid gap-6 md:grid-cols-3">
          {entity.cards.map((card, index) => (
            <FeatureCard
              key={card.title}
              badge={card.badge}
              gradient={gradientFor(index)}
              title={card.title}
              body={card.body}
              more={card.more}
            />
          ))}
        </div>
      </div>
    </section>
  );
}

/** Dark section: the governance claim is the one institutions actually interrogate. */
export function NationalTrustSection() {
  const messages = useMessages();
  const { trust } = messages.landing;
  return (
    <section id="national-trust" className="bg-navy">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading
          eyebrow={trust.eyebrow}
          title={trust.title}
          body={trust.body}
          inverted
        />
        <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-4">
          {trust.cards.map((card) => (
            <article
              key={card.title}
              className="rounded-xl border border-[#1e3d63] bg-[#0f2947] p-6"
            >
              <Eyebrow tone="pale">{card.badge}</Eyebrow>
              <h3 className="mb-2 text-xl font-bold text-white">{card.title}</h3>
              <p className="text-sm text-[#c4d7ec]">{card.body}</p>
            </article>
          ))}
        </div>

        {/* Back where it belongs, in the gap it left.
            It went to the exchange section for a few hours on the argument that the section
            explaining the network should carry the picture of it. Wrong on two counts. The
            exchange section is five numbered steps and a photograph under them is decoration
            below a diagram; and this image is dark and blue, which sat oddly on a pale surface
            and sits naturally here.

            The argument matters more than the colour. This section is the governance claim — the
            one institutions actually interrogate — and what the picture shows is a search across
            a network of institutions, which is precisely the capability the four cards above are
            promising is governed. A photograph next to a claim about restraint is doing work; the
            same photograph under a list of steps is filling space. */}
        <div className="mt-12 overflow-hidden rounded-2xl border border-[#1e3d63]">
          <Image
            src="/network-reach.webp"
            alt=""
            width={1536}
            height={1024}
            className="h-auto w-full"
            sizes="(max-width: 1024px) 100vw, 1024px"
          />
        </div>
      </div>
    </section>
  );
}

export function IndustriesSection() {
  const messages = useMessages();
  const { industries } = messages.landing;
  return (
    <section id="industries" className="mx-auto max-w-7xl px-6 py-14 md:py-20">
      <SectionHeading
        eyebrow={industries.eyebrow}
        title={industries.title}
        body={industries.body}
      />
      <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
        {industries.cards.map((card, index) => (
          <FeatureCard
            key={card.title}
            badge={card.badge}
            gradient={gradientFor(index)}
            title={card.title}
            body={card.body}
            more={card.more}
          />
        ))}
      </div>
    </section>
  );
}

export function PortfolioSection() {
  const messages = useMessages();
  const { portfolio } = messages.landing;
  const columns = portfolio.columns;
  return (
    <section className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading
          eyebrow={portfolio.eyebrow}
          title={portfolio.title}
          body={portfolio.body}
        />
        <div className="overflow-x-auto rounded-xl border border-line bg-white">
          <table className="w-full min-w-[36rem] text-left text-sm">
            <thead className="border-b border-line bg-[#f7f9fc] text-xs tracking-wide text-[#64748b] uppercase">
              <tr>
                {[
                  columns.entity,
                  columns.type,
                  columns.exposure,
                  columns.risk,
                  columns.status,
                ].map((heading) => (
                  <th key={heading} scope="col" className="px-5 py-3 font-semibold">
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {portfolio.rows.map((row) => (
                <tr key={row.entity} className="border-b border-line last:border-0">
                  <th scope="row" className="px-5 py-3.5 font-semibold text-navy">
                    {row.entity}
                  </th>
                  <td className="px-5 py-3.5 text-muted">{row.type}</td>
                  <td className="px-5 py-3.5 tabular-nums text-ink">{row.exposure}</td>
                  <td className="px-5 py-3.5 text-ink">{row.risk}</td>
                  <td className="px-5 py-3.5 text-muted">{row.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Beside the numbers, not in a footnote at the bottom of the page. A table of dollar
            figures reads as a report of something, and this one is a mock-up — the search board
            above says so about its own figures and this had nothing. */}
        <p className="mt-3 text-xs text-muted">{portfolio.note}</p>
      </div>
    </section>
  );
}

export function AiSection() {
  const messages = useMessages();
  const { ai } = messages.landing;
  return (
    <section id="ai" className="bg-navy text-white">
      <div className="mx-auto max-w-7xl px-6 py-16 md:py-24">
        <div className="mb-10 max-w-3xl">
          <p className="mb-3 text-xs font-semibold uppercase tracking-[0.18em] text-blue">
            {ai.eyebrow}
          </p>
          <h2 className="mb-4 text-3xl font-bold leading-tight md:text-4xl">{ai.title}</h2>
          <p className="text-base text-white/70">{ai.body}</p>
        </div>

        {/* The product's own question box, drawn rather than screenshotted, so it stays in step
            with the real one and stays legible at any width. */}
        <div className="mb-8 rounded-2xl border border-white/15 bg-white/5 p-5 backdrop-blur">
          <div className="flex flex-col gap-3 sm:flex-row">
            <div className="flex flex-1 items-center gap-3 rounded-lg bg-white px-4 py-3">
              <SparkMark />
              <span className="text-sm text-muted">{ai.prompt}</span>
            </div>
            <span className="rounded-lg bg-blue px-6 py-3 text-center text-sm font-semibold text-white">
              {ai.askAction}
            </span>
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            {ai.chips.map((chip) => (
              <span
                key={chip}
                className="rounded-full border border-white/20 px-3 py-1 text-xs text-white/70"
              >
                {chip}
              </span>
            ))}
          </div>
        </div>

        <div className="grid gap-6 md:grid-cols-2">
          {ai.exchanges.map((item) => (
            <article
              key={item.role}
              className="rounded-2xl border border-white/15 bg-white/5 p-6 transition hover:bg-white/10"
            >
              <p className="mb-3 text-xs font-semibold uppercase tracking-[0.18em] text-blue">
                {item.role}
              </p>
              <p className="mb-4 text-lg font-semibold">“{item.question}”</p>
              <p className="rounded-xl bg-white p-4 text-sm text-ink">
                <strong className="text-blue">{ai.answeredBy}: </strong>
                {item.answer}
              </p>
            </article>
          ))}
        </div>

        <p className="mt-6 max-w-3xl text-sm text-white/60">{ai.grounded}</p>
      </div>
    </section>
  );
}

/** The mark used beside the assistant, matching the one in the product. */
function SparkMark() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true" className="text-blue" fill="currentColor">
      <path d="M12 2l1.9 5.6L19.5 9.5 13.9 11.4 12 17l-1.9-5.6L4.5 9.5l5.6-1.9z" />
      <path d="M18.5 14l.9 2.6 2.6.9-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9z" opacity="0.6" />
    </svg>
  );
}

/**
 * Everything the platform does, on one screen.
 *
 * <p>A commercial page needs one place a reader can see the whole product rather than inferring it
 * from six themed sections. Twenty-four capabilities in six groups, and every one of them is in
 * {@code docs/BUILD_STATUS.md} under "running today" — that file holds the rule this section obeys:
 * confident is allowed, describing something that does not exist is not.
 *
 * <p>Grouped by what somebody is trying to do rather than by which module owns it. A reader
 * deciding whether to join a registry does not care that identity resolution and the risk model
 * live in different packages.
 */
export function CapabilitiesSection() {
  const messages = useMessages();
  const { capabilities } = messages.landing;
  return (
    <section id="capabilities" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-16 md:py-24">
        <SectionHeading
          eyebrow={capabilities.eyebrow}
          title={capabilities.title}
          body={capabilities.body}
        />

        <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
          {capabilities.groups.map((group) => (
            <article
              key={group.name}
              className="rounded-2xl border border-line bg-white p-7 transition hover:border-blue/40 hover:shadow-sm"
            >
              <h3 className="mb-4 text-lg font-bold text-navy">{group.name}</h3>
              <ul className="flex flex-col gap-3">
                {group.items.map((item) => (
                  <li key={item} className="flex gap-3 text-sm text-ink">
                    <span aria-hidden="true" className="mt-0.5 shrink-0 text-green">
                      ✓
                    </span>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

export function GovernanceSection() {
  const messages = useMessages();
  const { governance } = messages.landing;
  return (
    <section className="bg-white">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading
          eyebrow={governance.eyebrow}
          title={governance.title}
          body={governance.body}
        />
        <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-4">
          {governance.cards.map((card) => (
            <article key={card.title} className="rounded-xl border border-line bg-white p-6">
              <Eyebrow>{card.badge}</Eyebrow>
              <h3 className="mb-2 text-xl font-bold text-navy">{card.title}</h3>
              <p className="text-sm text-muted">{card.body}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

export function FinalCta() {
  const messages = useMessages();
  const { status, signIn } = useSession();
  const { finalCta, actions } = messages.landing;
  return (
    <section id="demo" className="bg-navy">
      <div className="mx-auto max-w-4xl px-6 py-14 md:py-20 text-center">
        <h2 className="mb-4 text-[clamp(2rem,4vw,3rem)] leading-tight font-bold text-white">
          {finalCta.title}
        </h2>
        <p className="mb-8 text-lg text-[#d7e4f4]">{finalCta.body}</p>
        <div className="flex flex-wrap justify-center gap-3">
          <a
            href="mailto:contact@dival.ai"
            className="rounded bg-blue px-6 py-3.5 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {finalCta.action}
          </a>
          {status !== "authenticated" && (
            <button
              type="button"
              onClick={() => signIn("/app")}
              className="rounded border border-[#5bb4ff] px-6 py-3.5 text-sm font-bold text-white transition hover:bg-[#0f2947]"
            >
              {actions.signIn}
            </button>
          )}
        </div>
      </div>
    </section>
  );
}

/**
 * What crosses the exchange and what never does.
 *
 * <p>The rest of the page sells sharing. This section is the other half of the argument and the
 * one an operator's legal team reaches first: a competing telecom joins because of what its
 * competitors will <em>not</em> learn. Two columns rather than prose, so the withheld list is as
 * prominent as the shared one.
 */
export function BoundarySection() {
  const messages = useMessages();
  const { boundary } = messages.landing;
  return (
    <section id="boundary" className="bg-white">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
        <SectionHeading
          eyebrow={boundary.eyebrow}
          title={boundary.title}
          body={boundary.body}
        />

        <div className="grid gap-6 lg:grid-cols-2">
          <article className="rounded-xl border border-line bg-white p-7">
            <h3 className="mb-4 text-xl font-bold text-navy">{boundary.receivedTitle}</h3>
            <ul className="flex flex-col gap-3">
              {boundary.received.map((item) => (
                <li key={item} className="flex gap-3 text-muted">
                  <span aria-hidden="true" className="font-extrabold text-green">
                    ✓
                  </span>
                  {item}
                </li>
              ))}
            </ul>
          </article>

          {/* The same visual weight as the column above it. A withheld list rendered as a
              footnote would read as a limitation rather than as the design. */}
          <article className="rounded-xl border-2 border-navy bg-navy p-7">
            <h3 className="mb-4 text-xl font-bold text-white">{boundary.withheldTitle}</h3>
            <ul className="flex flex-col gap-3">
              {boundary.withheld.map((item) => (
                <li key={item} className="flex gap-3 text-[#d7e4f4]">
                  <span aria-hidden="true" className="font-extrabold text-[#5bb4ff]">
                    ✕
                  </span>
                  {item}
                </li>
              ))}
            </ul>
          </article>
        </div>

        <div className="mt-6 grid gap-4 md:grid-cols-2">
          <p className="rounded-lg border border-line bg-white px-5 py-4 text-sm text-muted">
            {boundary.note}
          </p>
          <p className="rounded-lg border border-line bg-white px-5 py-4 text-sm text-muted">
            {boundary.purpose}
          </p>
        </div>
      </div>
    </section>
  );
}

/**
 * The rights of the people in the registry.
 *
 * <p>Everything else on this page is addressed to institutions. This is the only section written
 * about the people the registry is about, and it is on the page because the rights behind it are
 * built — cross-operator access, suppression the day a dispute is raised, erasure on a clock
 * running from the default date. A national bad-payer list that says nothing about data subjects
 * reads as though nobody considered them.
 */
export function SubjectRightsSection() {
  const messages = useMessages();
  const { subjects } = messages.landing;
  return (
    <section id="subjects" className="bg-white">
      <div className="mx-auto max-w-7xl px-6 py-14 md:py-20">
      <SectionHeading eyebrow={subjects.eyebrow} title={subjects.title} body={subjects.body} />

      <div className="grid gap-6 md:grid-cols-2">
        {subjects.cards.map((card) => (
          <article
            key={card.title}
            className="rounded-xl border border-line bg-white p-7 border-l-4 border-l-green"
          >
            <h3 className="mb-2 text-xl font-bold text-navy">{card.title}</h3>
            <p className="text-muted">{card.body}</p>
          </article>
        ))}
      </div>

      {/* The one photograph on this site that is of somebody who is not at work.
          It sits here and nowhere else, and the sentence beside it is the reason. A picture of a
          child on a page about a bad-payer registry either carries an argument or it is bait, and
          bait is expensive on a page whose entire posture is that its claims can be checked — a
          bank's risk director notices.

          The argument this section already makes is that a shared list decides who can open a
          line, take a loan or win a contract, over people who did not choose to be in it. What is
          behind an entry is a household. That is why the rights are built rather than promised,
          and it is also the case FOR a working registry in a market where most people have no
          credit file at all. Both halves are true and the second is the one banks forget. */}
      <div className="mt-10 grid items-center gap-8 rounded-xl border border-line bg-soft p-6 md:grid-cols-[1fr_1fr] md:p-8">
        <p className="text-lg leading-relaxed text-ink">{subjects.household}</p>
        <div className="overflow-hidden rounded-lg">
          <Image
            src="/a-household.webp"
            alt=""
            width={1536}
            height={1024}
            className="h-auto w-full"
            sizes="(max-width: 768px) 100vw, 480px"
          />
        </div>
      </div>

      {/* White on soft now that the section itself is soft; the note has to sit apart from
          the surface it is written on or it stops reading as an aside. */}
      <p className="mt-6 rounded-xl border border-line bg-white px-5 py-4 text-sm text-muted">
        {subjects.note}
      </p>
      </div>
    </section>
  );
}

/**
 * What runs, and what is still a drawing.
 *
 * <p>The most uncomfortable section to write and the most useful one to have. The rest of the
 * page describes a design in the present tense; the terms of reference commissioned a feasibility
 * study, and a page implying a live national registry is a liability rather than merely an
 * inaccuracy. Stating the gap plainly costs nothing with a bank and is the strongest thing a
 * regulator can be shown.
 */
export function LandingFooter() {
  const messages = useMessages();
  const { footer } = messages.landing;
  return (
    <footer className="border-t border-line bg-white">
      <div className="mx-auto max-w-7xl px-6 py-14">
        <div className="grid gap-8 sm:grid-cols-2 lg:grid-cols-5">
          {footer.groups.map((group) => (
            <div key={group.title}>
              <h3 className="mb-3 text-sm font-bold text-navy">{group.title}</h3>
              <ul className="flex flex-col gap-2 text-sm text-muted">
                {group.links.map((link) => (
                  <li key={link}>{link}</li>
                ))}
              </ul>
            </div>
          ))}
        </div>
        <div className="mt-10 border-t border-line pt-8">
          <SocialRow heading={footer.followUs} />
        </div>

        <div className="mt-8 border-t border-line pt-6 text-sm text-muted">
          <p>{footer.legal}</p>
          <p>{footer.tagline}</p>
        </div>
      </div>
    </footer>
  );
}
