/**
 * Small presentational pieces shared across the landing sections.
 *
 * <p>Kept together because they are meaningless on their own and only exist to stop the page
 * component from becoming a wall of repeated Tailwind strings.
 */

export function Eyebrow({
  children,
  tone = "blue",
}: {
  children: React.ReactNode;
  tone?: "blue" | "light" | "pale";
}) {
  const colour =
    tone === "blue" ? "text-blue" : tone === "light" ? "text-[#5bb4ff]" : "text-[#b8e1ff]";
  return (
    <div className={`mb-3.5 text-[13px] font-extrabold tracking-[0.12em] uppercase ${colour}`}>
      {children}
    </div>
  );
}

export function SectionHeading({
  eyebrow,
  title,
  body,
  inverted = false,
}: {
  eyebrow?: string;
  title: string;
  body?: string;
  inverted?: boolean;
}) {
  return (
    <div className="mb-10 max-w-3xl">
      {eyebrow && <Eyebrow tone={inverted ? "light" : "blue"}>{eyebrow}</Eyebrow>}
      <h2
        className={`mb-4 text-[clamp(2rem,4vw,3.25rem)] leading-[1.08] font-bold tracking-tight ${
          inverted ? "text-white" : "text-navy"
        }`}
      >
        {title}
      </h2>
      {body && <p className={`text-lg ${inverted ? "text-[#d7e4f4]" : "text-muted"}`}>{body}</p>}
    </div>
  );
}

/** The gradient-topped cards used by the platform and industry grids. */
export function FeatureCard({
  badge,
  gradient,
  title,
  body,
  more,
}: {
  badge: string;
  gradient: string;
  title: string;
  body: string;
  more: string;
}) {
  return (
    <article className="flex min-h-80 flex-col border border-line bg-white transition hover:-translate-y-1 hover:shadow-lg">
      <div
        className={`flex min-h-36 items-end p-6 text-[38px] leading-none font-extrabold text-white ${gradient}`}
      >
        {badge}
      </div>
      <div className="flex flex-1 flex-col p-6">
        <h3 className="mb-3 text-2xl leading-tight font-bold text-navy">{title}</h3>
        <p className="mb-4 text-muted">{body}</p>
        <span className="mt-auto font-bold text-blue">{more} →</span>
      </div>
    </article>
  );
}

export function CheckList({
  items,
  className = "",
}: {
  items: readonly string[];
  className?: string;
}) {
  return (
    <ul className={`flex flex-wrap gap-4.5 text-sm text-[#334155] ${className}`}>
      {items.map((item) => (
        <li key={item}>
          <span aria-hidden="true" className="mr-1.5 font-extrabold text-green">
            ✓
          </span>
          {item}
        </li>
      ))}
    </ul>
  );
}

export const CARD_GRADIENTS = [
  "bg-gradient-to-br from-[#0067b8] to-[#2b88d8]",
  "bg-gradient-to-br from-[#008c95] to-[#20b7a7]",
  "bg-gradient-to-br from-[#4f2d7f] to-[#8b5cf6]",
  "bg-gradient-to-br from-[#b74700] to-[#ff8c00]",
  "bg-gradient-to-br from-[#0b6a0b] to-[#36a336]",
  "bg-gradient-to-br from-[#0b1f3a] to-[#254d80]",
] as const;

export function gradientFor(index: number): string {
  return CARD_GRADIENTS[index % CARD_GRADIENTS.length] ?? CARD_GRADIENTS[0];
}
