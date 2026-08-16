"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { AskDip } from "@/components/AskDip";
import { PageHeader } from "@/components/ui";

/**
 * The Dival AI analyst: one box, one answer.
 *
 * <p>It used to carry a second feature — a search box that assembled an evidence pack about one
 * company — and it was the wrong shape. A screen that asks you to choose between two ways of
 * asking the same thing has made its own indecision the user's problem. The pack survives as the
 * answer to "why is this company risky", which is how somebody would have asked for it in words.
 *
 * <p><strong>The model reads the question; the platform computes the answer.</strong> Every figure
 * is a sum or a count over rows the caller is already entitled to, and the only generated text is
 * the sentence explicitly labelled as generated. That division is the reason this screen can exist
 * at all: a model handed a database connection would answer "which companies does the other
 * operator report" correctly and catastrophically, and it would look exactly like this.
 */
export default function AnalystPage() {
  const t = useMessages().analyst;

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />
      <AskDip />
    </div>
  );
}
