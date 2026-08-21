/**
 * Where Dival is, on the networks people look.
 *
 * <p><strong>One file, five lines to fill in.</strong> The URLs are deliberately empty and not
 * placeholder `#` links. A `#` renders as a real link, gets clicked, and does nothing — a visitor
 * reads that as a broken site rather than as an unfinished one, and it is the kind of thing that
 * survives to launch precisely because it looks finished.
 *
 * <p>An empty URL renders the icon greyed and unclickable, which is honest on an internal build and
 * obvious on a public one. Fill a URL in and that icon becomes a link with no other change.
 *
 * <p>Marks are drawn here rather than fetched from an icon package: five glyphs are not worth a
 * dependency that ships a thousand, and a font-based icon set is one CDN failure away from a row
 * of empty boxes in the footer.
 */

import type { ReactNode } from "react";

export type SocialLink = {
  key: string;
  /** Shown as the accessible name. Not translated: these are proper nouns. */
  name: string;
  /** Empty until somebody fills it in. See the note above on why not "#". */
  href: string;
  icon: ReactNode;
};

/** 24×24, currentColor, no fixed size — the footer decides how big they are. */
function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
      className="h-5 w-5"
    >
      {children}
    </svg>
  );
}

export const SOCIAL_LINKS: SocialLink[] = [
  {
    key: "linkedin",
    name: "LinkedIn",
    href: "",
    icon: (
      <Glyph>
        <path d="M4.98 3.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM3 9h4v12H3zM10 9h3.8v1.7h.05c.53-.95 1.83-1.95 3.77-1.95 4.03 0 4.78 2.5 4.78 5.76V21h-4v-5.6c0-1.34-.02-3.06-1.9-3.06-1.9 0-2.2 1.45-2.2 2.96V21h-4z" />
      </Glyph>
    ),
  },
  {
    key: "x",
    name: "X",
    href: "",
    icon: (
      <Glyph>
        <path d="M17.53 3h3.2l-6.99 7.99L22 21h-6.44l-5.04-6.6L4.75 21H1.54l7.48-8.55L2 3h6.6l4.56 6.03zm-1.12 16h1.77L7.68 4.83H5.78z" />
      </Glyph>
    ),
  },
  {
    key: "instagram",
    name: "Instagram",
    href: "",
    icon: (
      <Glyph>
        <path d="M12 2.2c3.2 0 3.58.01 4.85.07 1.17.05 1.8.25 2.23.41.56.22.96.48 1.38.9.42.42.68.82.9 1.38.16.42.36 1.06.41 2.23.06 1.27.07 1.65.07 4.85s-.01 3.58-.07 4.85c-.05 1.17-.25 1.8-.41 2.23-.22.56-.48.96-.9 1.38-.42.42-.82.68-1.38.9-.42.16-1.06.36-2.23.41-1.27.06-1.65.07-4.85.07s-3.58-.01-4.85-.07c-1.17-.05-1.8-.25-2.23-.41-.56-.22-.96-.48-1.38-.9-.42-.42-.68-.82-.9-1.38-.16-.42-.36-1.06-.41-2.23C2.21 15.58 2.2 15.2 2.2 12s.01-3.58.07-4.85c.05-1.17.25-1.8.41-2.23.22-.56.48-.96.9-1.38.42-.42.82-.68 1.38-.9.42-.16 1.06-.36 2.23-.41C8.42 2.21 8.8 2.2 12 2.2zm0 1.8c-3.15 0-3.5.01-4.74.07-1.14.05-1.76.24-2.17.4-.55.21-.94.47-1.35.88-.41.41-.67.8-.88 1.35-.16.41-.35 1.03-.4 2.17C2.4 10.1 2.4 10.45 2.4 12s.01 1.9.07 3.13c.05 1.14.24 1.76.4 2.17.21.55.47.94.88 1.35.41.41.8.67 1.35.88.41.16 1.03.35 2.17.4 1.24.06 1.59.07 4.73.07s3.5-.01 4.74-.07c1.14-.05 1.76-.24 2.17-.4.55-.21.94-.47 1.35-.88.41-.41.67-.8.88-1.35.16-.41.35-1.03.4-2.17.06-1.24.07-1.59.07-3.13s-.01-1.9-.07-3.13c-.05-1.14-.24-1.76-.4-2.17-.21-.55-.47-.94-.88-1.35-.41-.41-.8-.67-1.35-.88-.41-.16-1.03-.35-2.17-.4C15.5 4.01 15.15 4 12 4z" />
        <path d="M12 7.1a4.9 4.9 0 1 1 0 9.8 4.9 4.9 0 0 1 0-9.8zm0 1.8a3.1 3.1 0 1 0 0 6.2 3.1 3.1 0 0 0 0-6.2z" />
        <circle cx="17.1" cy="6.9" r="1.15" />
      </Glyph>
    ),
  },
  {
    key: "tiktok",
    name: "TikTok",
    href: "",
    icon: (
      <Glyph>
        <path d="M16.6 2h-3.1v13.2a2.6 2.6 0 1 1-2.6-2.6c.2 0 .4.02.6.06V9.5a5.7 5.7 0 1 0 5.1 5.67V8.6a6.4 6.4 0 0 0 3.9 1.3V6.8a3.5 3.5 0 0 1-3.9-3.4V2z" />
      </Glyph>
    ),
  },
  {
    key: "youtube",
    name: "YouTube",
    href: "",
    icon: (
      <Glyph>
        <path d="M21.6 7.2a2.5 2.5 0 0 0-1.76-1.77C18.27 5 12 5 12 5s-6.27 0-7.84.43A2.5 2.5 0 0 0 2.4 7.2 26.2 26.2 0 0 0 2 12a26.2 26.2 0 0 0 .4 4.8 2.5 2.5 0 0 0 1.76 1.77C5.73 19 12 19 12 19s6.27 0 7.84-.43a2.5 2.5 0 0 0 1.76-1.77A26.2 26.2 0 0 0 22 12a26.2 26.2 0 0 0-.4-4.8zM10 15.1V8.9l5.2 3.1z" />
      </Glyph>
    ),
  },
];

/**
 * The row of marks.
 *
 * <p>An icon with no URL is a {@code span}, not an {@code a}. That is the whole point of the file:
 * it is unmistakably not-yet-wired to anybody looking at it, and it cannot be tabbed to or clicked
 * and found to do nothing.
 *
 * <p>Every live one opens in a new tab with {@code rel="noopener noreferrer"} — {@code noopener}
 * because a page opened with {@code target="_blank"} can otherwise reach back through
 * {@code window.opener} and navigate this one, and {@code noreferrer} so the network is not told
 * which page of ours somebody came from.
 */
export function SocialRow({ heading }: { heading: string }) {
  return (
    <div>
      <h3 className="mb-3 text-sm font-bold text-navy">{heading}</h3>
      <ul className="flex flex-wrap gap-2">
        {SOCIAL_LINKS.map((link) =>
          link.href ? (
            <li key={link.key}>
              <a
                href={link.href}
                target="_blank"
                rel="noopener noreferrer"
                aria-label={link.name}
                title={link.name}
                className="flex h-9 w-9 items-center justify-center rounded-full border border-line text-muted transition hover:-translate-y-0.5 hover:border-blue hover:text-blue"
              >
                {link.icon}
              </a>
            </li>
          ) : (
            <li key={link.key}>
              <span
                aria-hidden="true"
                title={link.name}
                className="flex h-9 w-9 cursor-default items-center justify-center rounded-full border border-line/60 text-line"
              >
                {link.icon}
              </span>
            </li>
          ),
        )}
      </ul>
    </div>
  );
}
