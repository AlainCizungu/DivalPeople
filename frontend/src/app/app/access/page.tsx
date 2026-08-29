"use client";

import { useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  accessApi,
  type Access,
  type AccessMember,
  type Invitation,
  type MembershipOptions,
  type RoleAccess,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Pill,
  inputClass,
} from "@/components/ui";
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
 *
 * <p><strong>It is no longer read-only, and the note at the top still says it is.</strong> That
 * sentence is about roles held in the identity provider, which is still where they live — what
 * changed is that DIP can now ask the provider to change them on this institution's behalf. The
 * form appears only when a deployment has configured a service account for that, so on a
 * deployment without one this screen reads exactly as it did before.
 *
 * <p>The grantable roles come from the server. A copy of that list here would eventually disagree
 * with the rule the backend enforces — quietly, and in the direction of offering something that is
 * then refused.
 */
export default function AccessPage() {
  const messages = useMessages();
  const t = messages.access;

  const [access, setAccess] = useState<Access | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  /**
   * Whether this deployment lets an institution manage its own accounts.
   *
   * <p>Asked before anything is drawn, and its own failure is silent. A deployment with no service
   * account configured is a deployment decision rather than a fault, and the form is simply not
   * offered — a form that refuses at the moment somebody uses it teaches them the product is
   * broken instead.
   */
  const [membership, setMembership] = useState<MembershipOptions | null>(null);

  /** Bumped after a change, so the member list is re-read rather than patched in the browser. */
  const [reloads, setReloads] = useState(0);

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
    void (async () => {
      try {
        const options = await accessApi.membershipOptions();
        if (!cancelled) setMembership(options);
      } catch {
        // Older deployment, or a caller without the role. Either way the form stays away.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reloads]);

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

          {/* Only when the deployment can actually do it, and only for somebody entitled to see
              the member list at all. A form that appears and then refuses is worse than no form:
              it teaches an administrator that the product is unreliable rather than that this
              deployment has not been configured. */}
          {membership?.available && access.members !== null && (
            <InvitePanel
              grantable={membership.grantable}
              t={t}
              onInvited={() => setReloads((n) => n + 1)}
            />
          )}

          {membership !== null &&
            !membership.available &&
            access.members !== null && (
              <p className="rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
                {t.inviteUnavailable}
              </p>
            )}

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
                  <MemberRow
                    key={member.email}
                    member={member}
                    canManage={membership?.available ?? false}
                    t={t}
                    onChanged={() => setReloads((n) => n + 1)}
                  />
                ))}
              </div>
            )}
            {membership?.available && (
              <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
                {t.memberNoDelete}
              </p>
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
  canManage,
  t,
  onChanged,
}: {
  member: AccessMember;
  canManage: boolean;
  t: ReturnType<typeof useMessages>["access"];
  onChanged: () => void;
}) {
  const [working, setWorking] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);

  async function toggle() {
    setWorking(true);
    setFailed(null);
    try {
      await accessApi.setMemberActive(member.userId, !member.active);
      onChanged();
    } catch (caught) {
      setFailed(caught instanceof ApiError ? caught.message : String(caught));
    } finally {
      setWorking(false);
    }
  }

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

      {/* Suspend, never delete. The wording is the product's position rather than a euphemism:
          this person is the actor on every audit row they wrote, and removing the account makes
          that history unattributable. */}
      {canManage && (
        <button
          type="button"
          onClick={() => void toggle()}
          disabled={working}
          className={`rounded border px-3 py-1.5 text-xs font-semibold transition disabled:opacity-50 ${
            member.active
              ? "border-line text-ink hover:border-error hover:text-[#b91c1c]"
              : "border-line text-ink hover:border-blue hover:text-blue"
          }`}
        >
          {working
            ? t.memberChanging
            : member.active
              ? t.memberDisable
              : t.memberEnable}
        </button>
      )}

      {failed && <p className="w-full text-xs text-[#b91c1c]">{failed}</p>}
    </div>
  );
}

/**
 * Creating an account for a colleague.
 *
 * <p>The roles come from the server, never from a list in this file. Which roles may be granted is
 * a rule the backend enforces, and a copy of it here would disagree with it eventually — quietly,
 * and in the direction of offering something that is then refused.
 *
 * <p>The password is shown once. There is no way to ask for it again, which is the honest
 * consequence of not storing it: an administrator who loses it suspends the account and invites
 * again. That is worse than an email and better than a password this platform could be compelled
 * to produce later.
 */
function InvitePanel({
  grantable,
  t,
  onInvited,
}: {
  grantable: string[];
  t: ReturnType<typeof useMessages>["access"];
  onInvited: () => void;
}) {
  const messages = useMessages();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [roles, setRoles] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [created, setCreated] = useState<Invitation | null>(null);
  const [copied, setCopied] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();

    // Answered here rather than by the server, because the server's answer to this arrives as a
    // bean-validation 400 on `@Size(min = 1)` — a generic message about a field name, not the
    // sentence MembershipRules would have given. The rule is the server's; this is only the part
    // that saves a round trip to be told something the form already knows.
    if (roles.length === 0) {
      setFailed(t.inviteNeedsRole);
      return;
    }

    setBusy(true);
    setFailed(null);
    try {
      const invitation = await accessApi.invite({
        email: email.trim(),
        displayName: displayName.trim(),
        roles,
      });
      setCreated(invitation);
      setEmail("");
      setDisplayName("");
      setRoles([]);
      // The list is re-read rather than patched: the person appears only once they have signed
      // in, and pretending otherwise in the browser would be a lie the next reload corrects.
      onInvited();
    } catch (caught) {
      // The server's sentence, not a generic one. It says which role was refused and why, and
      // that is the whole value of refusing the request rather than dropping what it disliked.
      setFailed(caught instanceof ApiError ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  }

  if (created) {
    return (
      <Card
        title={interpolate(t.invitedTitle, t.invitedTitle, {
          email: created.email,
        })}
      >
        <p className="text-xs font-bold tracking-wide text-muted uppercase">
          {t.invitedPassword}
        </p>
        <div className="mt-1.5 flex flex-wrap items-center gap-3">
          <code className="rounded border border-line bg-soft px-3 py-2 font-mono text-sm text-ink">
            {created.password}
          </code>
          <Button
            variant="secondary"
            onClick={() => {
              void navigator.clipboard?.writeText(created.password);
              setCopied(true);
            }}
          >
            {copied ? t.invitedCopied : t.invitedCopy}
          </Button>
        </div>

        <p className="mt-4 text-sm leading-relaxed text-muted">
          {t.invitedOnce}
        </p>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          {t.invitedAppears}
        </p>

        <div className="mt-5">
          <Button
            onClick={() => {
              setCreated(null);
              setCopied(false);
            }}
          >
            {t.invitedDone}
          </Button>
        </div>
      </Card>
    );
  }

  return (
    <Card title={t.inviteTitle} description={t.inviteDescription}>
      <form onSubmit={submit} className="flex flex-col gap-5">
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label={t.inviteName} htmlFor="member-name">
            <input
              id="member-name"
              className={inputClass}
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              required
            />
          </Field>
          <Field label={t.inviteEmail} htmlFor="member-email">
            <input
              id="member-email"
              type="email"
              className={inputClass}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </Field>
        </div>

        <Field label={t.inviteRoles} hint={t.inviteRolesHint}>
          <div className="flex flex-wrap gap-2">
            {grantable.map((role) => {
              const chosen = roles.includes(role);
              return (
                <button
                  key={role}
                  type="button"
                  aria-pressed={chosen}
                  onClick={() =>
                    setRoles((current) =>
                      chosen
                        ? current.filter((r) => r !== role)
                        : [...current, role],
                    )
                  }
                  className={`rounded-full border px-3.5 py-1.5 text-sm font-semibold transition ${
                    chosen
                      ? "border-blue bg-blue text-white"
                      : "border-line bg-white text-ink hover:border-blue/50 hover:bg-soft"
                  }`}
                >
                  {role}
                </button>
              );
            })}
          </div>
        </Field>

        {failed && (
          <ErrorNotice>
            {t.inviteFailed} {failed}
          </ErrorNotice>
        )}

        {/*
          The button stays enabled when something is missing, and says what.

          It used to disable itself until a role was chosen, which produced the worst possible
          behaviour: filling in both fields, pressing the button, and having nothing happen at
          all. No error, no request, no clue that the role chips above were the thing standing in
          the way — they read as decoration rather than a required field. A control that refuses
          silently is indistinguishable from a broken one, and the person's next move is to
          conclude the product does not work.

          The empty-name and empty-address cases are left to the browser, which already points at
          the offending field. Roles have no native equivalent, so they get a sentence.
        */}
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={busy}>
            {busy ? messages.common.loading : t.inviteSubmit}
          </Button>
          {roles.length === 0 && (
            <span className="text-sm text-muted">{t.inviteNeedsRole}</span>
          )}
        </div>
      </form>
    </Card>
  );
}
