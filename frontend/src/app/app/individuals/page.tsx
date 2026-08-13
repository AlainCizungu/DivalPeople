"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { SubjectDirectory } from "@/components/directory/SubjectDirectory";

/**
 * People this institution has reported.
 *
 * <p>Empty today, and the empty state says why rather than looking broken: both real operator
 * deliveries are business portfolios. It is worth showing anyway. An empty list that explains
 * what would fill it — a subscriber file, ideally carrying a date of birth — makes the ask
 * concrete in a way a roadmap item never does, and a date of birth is the single field that
 * would make identity resolution work for people rather than only for companies.
 */
export default function IndividualsPage() {
  const t = useMessages().directory;
  return (
    <SubjectDirectory
      type="INDIVIDUAL"
      title={t.individualsTitle}
      subtitle={t.individualsSubtitle}
      emptyHint={t.emptyIndividuals}
    />
  );
}
