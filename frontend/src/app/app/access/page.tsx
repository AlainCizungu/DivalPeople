"use client";

import { useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  accessApi,
  type Access,
  type AccessMember,
  type RoleAccess,
} from "@/api/client";
import { Card, EmptyState, ErrorNotice, Pill } from "@/components/ui";
import { Band, CountUp } from "@/components/visual/motion";

/**
 * Who may do what here.
 *
 * <p>Two questions, and only one of them was answerable before. A tenant administrator could not
 * see what a role permitted before granting it, and somebody refused a screen was told they
 * lacked permission without being told which one — five screens still fall back to generic
 * wording for exactly that reason.
 *
 * <p><strong>The catalogue is generated from the guards, on every load.</strong> A written list of
 * what each role can do is right on the day it is written and wrong from the first guard anybody
 * changes, silently and in the reassuring direction. This is read off the annotations Spring is
 * enforcing, so the page and the behaviour cannot disagree — they are the same thing.
 */
export default function AccessPage() {
  const messages = useMessages();
  const t = messages.access;

  const [access, setAccess] = useState<Access | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await accessApi.load();
        if (!cancelled) setAccess(loaded);
      } catch (caught) {
        if (cancelled) return;
        setFailure(
          caught instanceof ApiError
            ? `${caught.status} ${caught.code} — ${caught.message}`
            : String(caught),
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Roles the caller holds first, then roles that grant something, then the rest. Somebody
  // opening this page is usually asking one of two questions — what do I have, or what would I be
  // giving somebody — and both are answered near the top.
  const roles = [...(access?.roles ?? [])].sort((a, b) => {
    if (a.held !== b.held) return a.held ? -1 : 1;
    return b.endpoints - a.endpoints;
  });

  /**
   * The widest role's reach, used to scale every bar.
   *
   * <p>Relative, not absolute, and deliberately so. Endpoint counts cannot be added together —
   * one endpoint guarded by three roles would be counted three times — so there is no honest
   * total to draw a percentage against. Comparing each role to the widest one is a real
   * comparison, and it is the one somebody about to grant a role actually wants.
   */
  const widest = Math.max(
    1,
    ...(access?.roles ?? []).map((role) => role.endpoints),
  );
  const held = roles.filter((role) => role.held).length;
  const guardNothing = roles.filter((role) => role.endpoints === 0).length;

  return (
    <div className="mx-auto max-w-5xl">
      {/* The band the other working screens carry. This one opened with a plain heading and a
          grey slab of caveats, so the first thing on a governance screen was two sentences of
          disclaimer and no figures at all. */}
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">
            {t.title}
          </h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {access && (
            <div className="flex flex-wrap gap-x-10 gap-y-4">
              {[
                [held, t.statHeld],
                [roles.length, t.statRoles],
                // Absent, not nought: a caller who may not see the member list is a different
                // thing from an organisation with nobody in it.
                ...(access.members === null
                  ? []
                  : [[access.members.length, t.statPeople] as const]),
                [guardNothing, t.statEmpty],
              ].map(([value, label]) => (
                <div key={label as string}>
                  <p className="text-3xl font-bold">
                    <CountUp value={value as number} />
                  </p>
                  <p className="text-xs text-white/60">{label as string}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      </Band>

      {/* Two sentences rather than a grey box. They are caveats about how the page is built, and
          a caveat set in the same weight as the content competes with it. */}
      <p className="mt-6 mb-6 text-sm leading-relaxed text-muted">
        {t.derivedNote} {t.readOnlyNote}
      </p>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && access === null && (
        <EmptyState>{messages.common.loading}</EmptyState>
      )}

      {access && (
        <div className="flex flex-col gap-6">
          <Card title={t.rolesTitle} description={t.rolesDescription}>
            <div className="flex flex-col gap-3">
              {roles.map((role) => (
                <RoleRow key={role.role} role={role} widest={widest} t={t} />
              ))}
            </div>
          </Card>

          <Card title={t.membersTitle} description={t.membersDescription}>
            {access.members === null ? (
              // Absent rather than empty. "Nobody is in your organisation" and "this is not
              // yours to see" are different statements, and the first is the reassuring one.
              <EmptyState>{t.noMembers}</EmptyState>
            ) : access.members.length === 0 ? (
              <EmptyState>{t.membersEmpty}</EmptyState>
            ) : (
              <div className="flex flex-col divide-y divide-line">
                {access.members.map((member) => (
                  <MemberRow key={member.email} member={member} t={t} />
                ))}
              </div>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

function RoleRow({
  role,
  widest,
  t,
}: {
  role: RoleAccess;
  widest: number;
  t: ReturnType<typeof useMessages>["access"];
}) {
  const authenticated = role.role === "AUTHENTICATED";
  const grantsNothing = role.endpoints === 0;

  return (
    <div
      className={`rounded-lg border p-4 ${
        role.held ? "border-blue/40 bg-blue/[0.03]" : "border-line"
      }`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-bold text-navy">
          {authenticated ? t.authenticatedRole : role.role}
        </span>
        {role.held && <Pill tone="positive">{t.yours}</Pill>}
        <span className="ml-auto text-xs text-muted tabular-nums">
          {interpolate(t.endpoints, t.endpoints, {
            count: String(role.endpoints),
          })}
          {role.heldBy !== null && (
            <>
              {" · "}
              {role.heldBy === 0
                ? t.heldByNobody
                : interpolate(t.heldBy, t.heldBy, {
                    count: String(role.heldBy),
                  })}
            </>
          )}
        </span>
      </div>

      {authenticated && (
        <p className="mt-1.5 text-xs text-muted">{t.authenticatedNote}</p>
      )}

      {/* How far this role reaches, against the widest one. Somebody about to grant a role is
          asking how much they are handing over, and "41 endpoints" answers that only for a reader
          who already knows what the numbers run to.

          Never coloured. Reach is not severity — a wide role is not a problem, it is a wide role
          — and this platform spends amber and red on things that are wrong. */}
      {!grantsNothing && (
        <div
          className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-line"
          role="img"
          aria-label={interpolate(t.reachLabel, t.reachLabel, {
            count: String(role.endpoints),
            most: String(widest),
          })}
        >
          <div
            className={`h-full rounded-full ${role.held ? "bg-blue" : "bg-blue/35"}`}
            style={{
              width: `${Math.max(2, Math.round((role.endpoints / widest) * 100))}%`,
            }}
          />
        </div>
      )}

      {grantsNothing ? (
        // A role with nothing behind it is worth a sentence rather than an empty row. It can be
        // assigned, and assigning it will change nothing for whoever receives it.
        <p className="mt-2 text-xs text-muted">{t.grantsNothing}</p>
      ) : (
        <ul className="mt-2.5 flex flex-wrap gap-1.5">
          {role.areas.map((area) => (
            <li
              key={area.name}
              className="rounded-full border border-line bg-soft px-2.5 py-0.5 text-xs text-ink"
            >
              {area.name}
              <span className="ml-1.5 text-muted tabular-nums">
                {area.endpoints}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function MemberRow({
  member,
  t,
}: {
  member: AccessMember;
  t: ReturnType<typeof useMessages>["access"];
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 py-3">
      <div className="min-w-0">
        <p
          className={
            member.active ? "font-semibold text-ink" : "text-muted line-through"
          }
        >
          {member.displayName || member.email}
        </p>
        <p className="text-xs text-muted">{member.email}</p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {member.roles.map((role) => (
          <Pill key={role}>{role}</Pill>
        ))}
        {!member.active && <Pill tone="serious">{t.inactive}</Pill>}
      </div>

      <span className="ml-auto text-xs text-muted">
        {member.lastSeenAt ? member.lastSeenAt.slice(0, 10) : t.never}
      </span>
    </div>
  );
}
