"use client";

import { useMessages } from "@/i18n/LocaleProvider";

/**
 * The blue band above everything.
 *
 * <p>In the original design and lost somewhere in the rebuild, along with the four-square mark
 * beside the wordmark. Both are worth having back for the same reason: the page currently opens
 * on white, and a visitor's first impression of a platform asking institutions to trust it with a
 * national dataset should not be that it looks unfinished.
 *
 * <p>It is a statement of purpose rather than a promotion — no dismiss button, nothing to click,
 * no urgency. A band that can be closed is a band people close.
 */
export function AnnounceBar() {
  const messages = useMessages();
  return (
    <div className="bg-blue px-4 py-2.5 text-center text-sm font-semibold text-white">
      {messages.landing.announce}
    </div>
  );
}
