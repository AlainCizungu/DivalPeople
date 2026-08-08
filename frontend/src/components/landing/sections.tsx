"use client";

import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
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
        {messages.landing.quick.map((item) => (
          <span key={item}>{item}</span>
        ))}
      </div>
    </div>
  );
}

export function PlatformSection() {
  const messages = useMessages();
  const { platform } = messages.landing;
  return (
    <section id="platform" className="mx-auto max-w-7xl px-6 py-20">
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
      <div className="mx-auto max-w-7xl px-6 py-20">
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
    <section id="risk" className="mx-auto max-w-7xl px-6 py-20">
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
                [risk.exposureLabel, risk.exposureValue],
                [risk.confidenceLabel, risk.confidenceValue],
              ].map(([label, value]) => (
                <div key={label} className="rounded-lg border border-[#e8edf3] bg-[#f7f9fc] p-3">
                  <dt className="text-xs text-[#64748b]">{label}</dt>
                  <dd className="mt-1 text-xl font-bold tabular-nums text-navy">{value}</dd>
                </div>
              ))}
            </dl>

            <div className="flex flex-col gap-3.5">
              {risk.bars.map((bar) => (
                <div key={bar.label}>
                  <div className="mb-1.5 flex justify-between text-sm">
                    <span className="text-ink">{bar.label}</span>
                    <span className="font-bold tabular-nums text-navy">{bar.value}</span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-[#eef2f6]">
                    {/* Inline width because the value is data, not a design token — Tailwind
                        cannot generate a class for a number it never sees at build time. */}
                    <div
                      className="h-full rounded-full bg-blue"
                      style={{ width: `${Number(bar.value)}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

export function EntitySection() {
  const messages = useMessages();
  const { entity } = messages.landing;
  return (
    <section className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-20">
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
      <div className="mx-auto max-w-7xl px-6 py-20">
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
      </div>
    </section>
  );
}

export function IndustriesSection() {
  const messages = useMessages();
  const { industries } = messages.landing;
  return (
    <section id="industries" className="mx-auto max-w-7xl px-6 py-20">
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
      <div className="mx-auto max-w-7xl px-6 py-20">
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
      </div>
    </section>
  );
}

export function AiSection() {
  const messages = useMessages();
  const { ai } = messages.landing;
  return (
    <section id="ai" className="mx-auto max-w-7xl px-6 py-20">
      <SectionHeading eyebrow={ai.eyebrow} title={ai.title} body={ai.body} />
      <div className="grid gap-6 md:grid-cols-2">
        {ai.exchanges.map((item) => (
          <article key={item.role} className="rounded-xl border border-line bg-white p-6">
            <Eyebrow>{item.role}</Eyebrow>
            <p className="mb-4 text-lg font-semibold text-navy">“{item.question}”</p>
            <p className="rounded-lg bg-soft p-4 text-sm text-ink">
              <strong className="text-blue">{ai.answeredBy}: </strong>
              {item.answer}
            </p>
          </article>
        ))}
      </div>
    </section>
  );
}

export function GovernanceSection() {
  const messages = useMessages();
  const { governance } = messages.landing;
  return (
    <section className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-20">
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
      <div className="mx-auto max-w-4xl px-6 py-20 text-center">
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
    <section id="boundary" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-20">
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
    <section id="subjects" className="mx-auto max-w-7xl px-6 py-20">
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

      <p className="mt-6 rounded-lg border border-line bg-soft px-5 py-4 text-sm text-muted">
        {subjects.note}
      </p>
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
export function StatusSection() {
  const messages = useMessages();
  const { status } = messages.landing;

  const columns = [
    { title: status.builtTitle, items: status.built, accent: "border-l-green", mark: "✓" },
    { title: status.designedTitle, items: status.designed, accent: "border-l-blue", mark: "○" },
    { title: status.openTitle, items: status.open, accent: "border-l-warning", mark: "?" },
  ];

  return (
    <section id="status" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-20">
        <SectionHeading eyebrow={status.eyebrow} title={status.title} body={status.body} />

        <div className="grid gap-6 lg:grid-cols-3">
          {columns.map((column) => (
            <article
              key={column.title}
              className={`rounded-xl border border-line border-l-4 bg-white p-7 ${column.accent}`}
            >
              <h3 className="mb-4 text-lg font-bold text-navy">{column.title}</h3>
              <ul className="flex flex-col gap-3 text-sm text-muted">
                {column.items.map((item) => (
                  <li key={item} className="flex gap-3">
                    <span aria-hidden="true" className="font-extrabold text-ink">
                      {column.mark}
                    </span>
                    {item}
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </div>

        <p className="mt-6 rounded-lg border-2 border-navy bg-white px-5 py-4 font-semibold text-navy">
          {status.note}
        </p>
      </div>
    </section>
  );
}

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
        <div className="mt-10 border-t border-line pt-6 text-sm text-muted">
          <p>{footer.legal}</p>
          <p>{footer.tagline}</p>
        </div>
      </div>
    </footer>
  );
}
