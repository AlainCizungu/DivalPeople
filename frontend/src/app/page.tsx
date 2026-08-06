"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { Hero } from "@/components/landing/Hero";
import {
  FinalCta,
  FinancialSection,
  FraudSection,
  IndustriesSection,
  LandingFooter,
  LifecycleSection,
  PlatformSection,
  QuickLinks,
} from "@/components/landing/sections";

/**
 * Public marketing page. No authentication required — the product lives under /app.
 */
export default function LandingPage() {
  const messages = useMessages();

  return (
    <div className="bg-white">
      <p className="bg-blue px-4 py-2.5 text-center text-sm font-semibold text-white">
        {messages.landing.announce}
      </p>

      <LandingHeader />

      <main>
        <Hero />
        <QuickLinks />
        <PlatformSection />
        <LifecycleSection />
        <FinancialSection />
        <FraudSection />
        <IndustriesSection />
        <FinalCta />
      </main>

      <LandingFooter />
    </div>
  );
}
