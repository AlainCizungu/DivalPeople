"use client";

import { AuthErrorNotice } from "@/components/landing/AuthErrorNotice";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { Hero } from "@/components/landing/Hero";
import {
  AiSection,
  EntitySection,
  ExchangeSection,
  FinalCta,
  GovernanceSection,
  IndustriesSection,
  LandingFooter,
  NationalTrustSection,
  PlatformSection,
  PortfolioSection,
  QuickLinks,
  RiskSection,
} from "@/components/landing/sections";

/**
 * Public marketing page. No authentication required — the product lives under /app.
 *
 * <p>Order matters and follows the argument the page is making: what DIP is, how data gets in,
 * what comes out, who it is for, and only then how it is governed. Governance last is deliberate;
 * leading with it answers an objection nobody has raised yet.
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
        <ExchangeSection />
        <RiskSection />
        <EntitySection />
        <NationalTrustSection />
        <IndustriesSection />
        <PortfolioSection />
        <AiSection />
        <GovernanceSection />
        <FinalCta />
      </main>

      <LandingFooter />
    </div>
  );
}
