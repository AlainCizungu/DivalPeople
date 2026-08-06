"use client";

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
