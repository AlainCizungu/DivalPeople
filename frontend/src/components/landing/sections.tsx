"use client";

import { useAuth } from "react-oidc-context";
import { useMessages } from "@/i18n/LocaleProvider";
import { CheckList, Eyebrow, FeatureCard, SectionHeading, gradientFor } from "./primitives";

/** The five shortcut tiles that overlap the hero. */
export function QuickLinks() {
  const { landing } = useMessages();
  return (
    <div className="mx-auto -mt-7 mb-16 grid max-w-6xl grid-cols-2 gap-3.5 px-6 md:grid-cols-5">
      {landing.quick.map((label) => (
        <a
          key={label}
          href="#platform"
          className="border border-line bg-white p-5 text-center font-bold shadow-md transition hover:text-blue"
        >
          {label}
        </a>
      ))}
    </div>
  );
}

export function PlatformSection() {
  const { landing } = useMessages();
  return (
    <section id="platform" className="mx-auto max-w-7xl px-6 py-20">
      <SectionHeading
        eyebrow={landing.platform.eyebrow}
        title={landing.platform.title}
        body={landing.platform.body}
      />
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {landing.platform.cards.map((card, index) => (
          <FeatureCard key={card.title} gradient={gradientFor(index)} {...card} />
        ))}
      </div>
    </section>
  );
}

export function LifecycleSection() {
  const { landing } = useMessages();
  return (
    <section id="hr" className="mx-auto max-w-7xl px-6 py-20">
      <SectionHeading eyebrow={landing.lifecycle.eyebrow} title={landing.lifecycle.title} />
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {landing.lifecycle.tiles.map((tile) => (
          <div key={tile.title} className="min-h-56 border border-line bg-white p-7">
            <span className="mb-4 inline-block bg-[#eaf3fb] px-2.5 py-1 text-xs font-extrabold text-blue">
              {tile.tag}
            </span>
            <h3 className="mb-3 text-2xl font-bold text-navy">{tile.title}</h3>
            <p className="text-muted">{tile.body}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * Artificial intelligence.
 *
 * <p>The safeguards are given equal weight to the capabilities on purpose. "AI is advisory,
 * humans decide" is a rule the platform actually enforces (see docs/SECURITY_MODEL.md), so
 * stating it plainly is accurate rather than decorative — and for buyers in regulated sectors
 * it is the part that matters most.
 */
export function AiSection() {
  const { landing } = useMessages();
  const { ai } = landing;

  return (
    <section id="ai" className="bg-soft">
      <div className="mx-auto max-w-7xl px-6 py-20">
        <SectionHeading eyebrow={ai.eyebrow} title={ai.title} body={ai.body} />

        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
          {ai.capabilities.map((capability, index) => (
            <article
              key={capability.title}
              className="flex flex-col border border-line bg-white p-6"
            >
              <span
                className={`mb-4 w-fit px-2.5 py-1 text-xs font-extrabold tracking-wider text-white ${gradientFor(index)}`}
              >
                {capability.badge}
              </span>
              <h3 className="mb-3 text-xl font-bold text-navy">{capability.title}</h3>
              <p className="text-muted">{capability.body}</p>
            </article>
          ))}
        </div>

        <div className="mt-8 border-l-4 border-teal bg-white p-8">
          <h3 className="mb-4 text-xl font-bold text-navy">{ai.safeguards.title}</h3>
          <ul className="grid gap-3 sm:grid-cols-2">
            {ai.safeguards.items.map((item) => (
              <li key={item} className="flex gap-2.5 text-ink">
                <span aria-hidden="true" className="font-extrabold text-teal">
                  ✓
                </span>
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

export function FinancialSection() {
  const { landing } = useMessages();
  const icons = ["⚡", "🏦", "🩺", "💰"];

  return (
    <section id="financial" className="bg-navy">
      <div className="mx-auto max-w-7xl px-6 py-20">
        <SectionHeading
          eyebrow={landing.financial.eyebrow}
          title={landing.financial.title}
          body={landing.financial.body}
          inverted
        />

        <div className="grid gap-7 lg:grid-cols-[1.1fr_0.9fr]">
          <div className="flex min-h-[27rem] flex-col justify-end bg-gradient-to-br from-[#0078d4] to-[#005a9e] p-10">
            <Eyebrow tone="pale">{landing.financial.feature.eyebrow}</Eyebrow>
            <h3 className="mb-3.5 text-[2.5rem] leading-tight font-bold text-white">
              {landing.financial.feature.title}
            </h3>
            <p className="mb-6 text-lg text-[#eef7ff]">{landing.financial.feature.body}</p>
            <a
              href="#demo"
              className="w-fit rounded border border-white bg-transparent px-5 py-3 text-sm font-bold text-white transition hover:bg-white hover:text-navy"
            >
              {landing.financial.feature.cta}
            </a>
          </div>

          <div className="grid gap-4.5 sm:grid-cols-2">
            {landing.financial.minis.map((mini, index) => (
              <div key={mini.title} className="min-h-52 bg-white p-6">
                <b aria-hidden="true" className="text-3xl">
                  {icons[index]}
                </b>
                <h4 className="mt-3 mb-2 text-xl font-bold text-navy">{mini.title}</h4>
                <p className="text-muted">{mini.body}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}

export function FraudSection() {
  const { landing } = useMessages();
  const { fraud } = landing;
  // Mirrors the prototype's sample panel: the last row is the deliberate "clear" case, and the
  // third is a softer review state, so the panel does not read as all-alarms.
  const severityFor = (index: number) =>
    index === 4
      ? { label: fraud.panel.clear, className: "bg-[#e7f6ec] text-green" }
      : index === 2
        ? { label: fraud.panel.review, className: "bg-[#e7f6ec] text-green" }
        : { label: fraud.panel.high, className: "bg-[#fff1f0] text-error" };

  return (
    <section id="security" className="mx-auto max-w-7xl px-6 py-20">
      <div className="grid items-center gap-16 lg:grid-cols-2">
        <div>
          <Eyebrow>{fraud.eyebrow}</Eyebrow>
          <h2 className="mb-4 text-[clamp(2rem,4vw,3.25rem)] leading-[1.08] font-bold tracking-tight text-navy">
            {fraud.title}
          </h2>
          <p className="mb-6 text-lg text-muted">{fraud.body}</p>
          <CheckList items={fraud.checks} />
          <a
            href="#demo"
            className="mt-7 inline-block rounded bg-blue px-5 py-3.5 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {fraud.cta}
          </a>
        </div>

        <div className="border border-line bg-[#f7f9fc] p-5 shadow-lg">
          <div className="mb-3.5 flex justify-between">
            <strong className="text-navy">{fraud.panel.title}</strong>
            <span className="text-sm text-muted">{fraud.panel.openAlerts}</span>
          </div>
          {fraud.panel.rows.map((row, index) => {
            const severity = severityFor(index);
            return (
              <div
                key={row.name}
                className="grid grid-cols-[1fr_110px_80px] items-center gap-2.5 border-b border-line py-3 text-sm"
              >
                <strong className="text-ink">{row.name}</strong>
                <span className="text-muted">{row.area}</span>
                <span className={`p-1 text-center font-bold ${severity.className}`}>
                  {severity.label}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

export function IndustriesSection() {
  const { landing } = useMessages();
  return (
    <section id="industries" className="mx-auto max-w-7xl px-6 py-20">
      <SectionHeading
        eyebrow={landing.industries.eyebrow}
        title={landing.industries.title}
        body={landing.industries.body}
      />
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {landing.industries.cards.map((card, index) => (
          <FeatureCard key={card.title} gradient={gradientFor(index + 5)} {...card} />
        ))}
      </div>
    </section>
  );
}

export function FinalCta() {
  const { landing } = useMessages();
  const auth = useAuth();

  return (
    <section id="demo" className="mx-auto my-16 max-w-7xl px-6">
      <div className="flex flex-col items-start justify-between gap-7 bg-gradient-to-r from-[#eef6ff] to-[#e8fbf4] p-14 md:flex-row md:items-center">
        <div>
          <h2 className="mb-2.5 text-[2.6rem] leading-[1.08] font-bold text-navy">
            {landing.finalCta.title}
          </h2>
          <p className="text-lg text-muted">{landing.finalCta.body}</p>
        </div>
        <button
          type="button"
          onClick={() => void auth.signinRedirect()}
          className="shrink-0 rounded bg-blue px-6 py-3.5 text-sm font-bold text-white transition hover:bg-blue-dark"
        >
          {landing.finalCta.button}
        </button>
      </div>
    </section>
  );
}

export function LandingFooter() {
  const { landing } = useMessages();
  return (
    <footer className="bg-[#f2f2f2] px-6 pt-12 pb-6 text-[#4b5563]">
      <div className="mx-auto grid max-w-7xl grid-cols-2 gap-7 md:grid-cols-5">
        {landing.footer.columns.map((column) => (
          <div key={column.title}>
            <h4 className="font-bold text-ink">{column.title}</h4>
            {column.links.map((link) => (
              <span key={link} className="my-2 block text-[13px]">
                {link}
              </span>
            ))}
          </div>
        ))}
      </div>
      <div className="mx-auto mt-7 flex max-w-7xl flex-col gap-2 border-t border-[#d4d4d4] pt-5 text-xs md:flex-row md:justify-between">
        <span>{landing.footer.copyright}</span>
        <span>{landing.footer.builtOn}</span>
      </div>
    </footer>
  );
}
