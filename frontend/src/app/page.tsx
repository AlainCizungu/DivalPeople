"use client";

import { AuthErrorNotice } from "@/components/landing/AuthErrorNotice";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { Hero } from "@/components/landing/Hero";
import {
  AiSection,
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
  return (
    <div className="bg-white">
      {/* Above the header, because a failed sign-in is the most important thing on the page to
          somebody who has just been silently returned to it. */}
      <AuthErrorNotice />
      <LandingHeader />

      <main>
        <Hero />
        <QuickLinks />
        <PlatformSection />
        <LifecycleSection />
        <AiSection />
        <FinancialSection />
        <FraudSection />
        <IndustriesSection />
        <FinalCta />
      </main>

      <LandingFooter />
    </div>
  );
}
