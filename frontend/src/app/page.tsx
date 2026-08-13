"use client";

import { AnnounceBar } from "@/components/landing/AnnounceBar";
import { AuthErrorNotice } from "@/components/landing/AuthErrorNotice";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { Hero } from "@/components/landing/Hero";
import {
  AiSection,
  BoundarySection,
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
  StatusSection,
  SubjectRightsSection,
} from "@/components/landing/sections";

/**
 * Public marketing page. No authentication required — the product lives under /app.
 *
 * <p>Order matters and follows the argument the page is making: what DIP is, how data gets in,
 * what comes out, who it is for, and only then how it is governed. Governance last is deliberate;
 * leading with it answers an objection nobody has raised yet.
 *
 * <p>Three sections were added once there was a real product to describe. {@code Boundary} follows
 * the exchange because the question it answers — what will my competitors learn — is the next one
 * a reader has. {@code SubjectRights} precedes governance, since the rights are the substance and
 * governance is the machinery around them. {@code Status} sits last before the call to action: a
 * page that has just described a national platform should say which parts of it exist before it
 * asks anybody to get in touch.
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
        <PlatformSection />
        <ExchangeSection />
        <BoundarySection />
        <RiskSection />
        <EntitySection />
        <NationalTrustSection />
        <IndustriesSection />
        <PortfolioSection />
        <AiSection />
        <SubjectRightsSection />
        <GovernanceSection />
        <StatusSection />
        <FinalCta />
      </main>

      <LandingFooter />
    </div>
  );
}
