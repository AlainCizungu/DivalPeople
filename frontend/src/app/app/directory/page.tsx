"use client";

import { Directory } from "@/components/dashboard/Directory";
import { useMessages } from "@/i18n/LocaleProvider";
import { Band } from "@/components/visual/motion";

/**
 * Everything DIP contains, on its own page.
 *
 * <p>It used to be the last section of the overview, and it was the wrong thing at the bottom of
 * that page. The overview answers "what needs me this morning"; this answers "what is in this
 * product", which is a question somebody asks twice in their first week and rarely again. Sitting
 * below the figures it pushed the real content up and away, and every scroll to the bottom of the
 * front door ended in a site map.
 *
 * <p>Reached from Help → Explore DIP in the top bar, which is where somebody with that question
 * looks. Deliberately not added to the navigation catalogue: the catalogue is the list of places
 * to work, and an entry for the list-of-places-to-work belongs in help rather than inside itself.
 *
 * <p>Renders unconditionally and calls nothing. It is built from the same catalogue the menu is,
 * so it works when the server is unreachable — a person who cannot load their dashboard can still
 * find out where things are, which is exactly when they are most likely to be looking.
 */
export default function DirectoryPage() {
  const messages = useMessages();

  return (
    <div className="mx-auto max-w-5xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {messages.app.name}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">
            {messages.dashboard.directory.title}
          </h1>
          <p className="max-w-2xl text-sm text-white/70">
            {messages.dashboard.directory.subtitle}
          </p>
        </div>
      </Band>

      <div className="mt-8">
        <Directory />
      </div>
    </div>
  );
}
