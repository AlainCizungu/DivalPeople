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
import { Card, EmptyState, ErrorNotice, PageHeader, Pill } from "@/components/ui";

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

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <p className="mb-6 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.derivedNote} {t.readOnlyNote}
      </p>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && access === null && <EmptyState>{messages.common.loading}</EmptyState>}

      {access && (
        <div className="flex flex-col gap-6">
          <Card title={t.rolesTitle} description={t.rolesDescription}>
            <div className="flex flex-col gap-3">
              {roles.map((role) => (
                <RoleRow key={role.role} role={role} t={t} />
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
  t,
}: {
  role: RoleAccess;
  t: ReturnType<typeof useMessages>["access"];
}) {
  const authenticated = role.role === "AUTHENTICATED";
  const grantsNothing = role.endpoints === 0;

  return (
    <div className="rounded-lg border border-line p-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-bold text-navy">
          {authenticated ? t.authenticatedRole : role.role}
        </span>
        {role.held && <Pill tone="positive">{t.yours}</Pill>}
        <span className="ml-auto text-xs text-muted tabular-nums">
          {interpolate(t.endpoints, t.endpoints, { count: String(role.endpoints) })}
          {role.heldBy !== null && (
            <>
              {" · "}
              {role.heldBy === 0
                ? t.heldByNobody
                : interpolate(t.heldBy, t.heldBy, { count: String(role.heldBy) })}
            </>
          )}
        </span>
      </div>

      {authenticated && <p className="mt-1.5 text-xs text-muted">{t.authenticatedNote}</p>}

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
              <span className="ml-1.5 text-muted tabular-nums">{area.endpoints}</span>
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
        <p className={member.active ? "font-semibold text-ink" : "text-muted line-through"}>
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
