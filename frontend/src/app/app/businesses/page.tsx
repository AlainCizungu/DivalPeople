"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { SubjectDirectory } from "@/components/directory/SubjectDirectory";

/**
 * Companies this institution has reported.
 *
 * <p>The half of the directory that has data. Both real operator deliveries are business
 * portfolios, so this is where the 4,290 Vodacom rows and the 342 Orange ones end up.
 */
export default function BusinessesPage() {
  const t = useMessages().directory;
  return (
    <SubjectDirectory
      type="BUSINESS"
      title={t.businessesTitle}
      subtitle={t.businessesSubtitle}
      emptyHint={t.emptyBusinesses}
    />
  );
}
