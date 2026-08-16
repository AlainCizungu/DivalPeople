"use client";

import { AnnounceBar } from "@/components/landing/AnnounceBar";
import { AuthErrorNotice } from "@/components/landing/AuthErrorNotice";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { Hero } from "@/components/landing/Hero";
import {
  AiSection,
  BoundarySection,
  CapabilitiesSection,
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
  SubjectRightsSection,
} from "@/components/landing/sections";

/**
 * Public marketing page. No authentication required — the product lives under /app.
 *
 * <p>Order matters and follows the argument the page is making: what DIP is, how data gets in,
 * what comes out, who it is for, and only then how it is governed. Governance last is deliberate;
 * leading with it answers an objection nobody has raised yet.
 *
 * <p>The assistant comes second, immediately after the hero. It is the thing a visitor remembers
 * and the thing no competing registry in the region has, and burying it under six sections about
 * data governance was selling the plumbing ahead of the product.
 *
 * <p>{@code Boundary} follows the exchange because the question it answers — what will my
 * competitors learn — is the next one a reader has. {@code SubjectRights} precedes governance,
 * since the rights are the substance and governance is the machinery around them.
 *
 * <p>What is <em>not</em> here: the build-status columns that used to sit before the call to
 * action, listing what runs, what is designed and what is undecided. They moved to
 * {@code docs/BUILD_STATUS.md}, and the rule came with them — everything this page advertises
 * appears in that file's "running today" list, and nothing may be added here before it does. A
 * commercial page is allowed to be confident. It is not allowed to describe something that does
 * not exist.
 */
export default function LandingPage() {
  return (
    <div className="bg-white">
      {/* Above the header, because a failed sign-in is the most important thing on the page to
          somebody who has just been silently returned to it. */}
      <AuthErrorNotice />
      <AnnounceBar />
      <LandingHeader />

      <main>
        <Hero />
        <QuickLinks />
        <AiSection />
        <CapabilitiesSection />
        <PlatformSection />
        <ExchangeSection />
        <BoundarySection />
        <RiskSection />
        <EntitySection />
        <NationalTrustSection />
        <IndustriesSection />
        <PortfolioSection />
        <SubjectRightsSection />
        <GovernanceSection />
        <FinalCta />
      </main>

      <LandingFooter />
    </div>
  );
}
