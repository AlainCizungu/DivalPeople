"use client";

import { useCallback, useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  participantsApi,
  type Edition,
  type Participant,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Metric,
  PageHeader,
  Pill,
  inputClass,
} from "@/components/ui";

const EDITIONS: Edition[] = [
  "TELECOM",
  "BANKING",
  "GOVERNMENT",
  "NGO",
  "HEALTHCARE",
  "ENTERPRISE",
];

/**
 * Who is on the exchange.
 *
 * <p>The screen that answers "which organisations does this serve", and it needed no new backend:
 * the tenant API has existed since the platform was built and nothing had ever called it.
 *
 * <p>Guarded by PLATFORM_ADMIN on the server. A participant list is not a neutral fact — it tells
 * you which of your competitors have joined and which have not — so it belongs to whoever runs the
 * network rather than to its members.
 */
export default function ParticipantsPage() {
  const messages = useMessages();
  const t = messages.participants;

  const [participants, setParticipants] = useState<Participant[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [edition, setEdition] = useState<Edition>("TELECOM");

  const load = useCallback(async () => {
    try {
      setParticipants(await participantsApi.list());
    } catch (caught) {
      setParticipants([]);
      setError(
        caught instanceof ApiError && caught.status === 403
          ? t.needPlatformAdmin
          : caught instanceof ApiError
            ? caught.message
            : messages.common.unexpectedError,
      );
    }
  }, [messages.common.unexpectedError, t.needPlatformAdmin]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    } finally {
      setBusy(false);
    }
  }

  const total = participants?.length ?? 0;
  const active = participants?.filter((p) => p.active).length ?? 0;
  const contributing = participants?.filter((p) => p.active).length ?? 0;
  const editions = new Set((participants ?? []).map((p) => p.edition)).size;
  const value = (n: number) => (participants === null ? "—" : String(n));

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <Metric label={t.total} value={value(total)} />
        <Metric label={t.active} value={value(active)} note={t.activeNote} />
        <Metric label={t.sectors} value={value(editions)} />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1.4fr_1fr]">
        <Card title={t.listTitle} description={t.listDescription}>
          {participants === null ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : participants.length === 0 ? (
            <EmptyState>{t.empty}</EmptyState>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[34rem] text-left text-sm">
                <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="pb-3 font-semibold">{t.organisation}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.sector}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.status}</th>
                    <th scope="col" className="pb-3" />
                  </tr>
                </thead>
                <tbody>
                  {participants.map((participant) => (
                    <tr key={participant.id} className="border-b border-line last:border-0">
                      <th scope="row" className="py-3.5 font-semibold text-navy">
                        {participant.name}
                        <span className="ml-2 font-normal text-muted">{participant.slug}</span>
                      </th>
                      <td className="py-3.5">
                        <Pill>{t.editions[participant.edition]}</Pill>
                      </td>
                      <td className="py-3.5">
                        <Pill tone={participant.active ? "positive" : "neutral"}>
                          {participant.active ? t.statusActive : t.statusSuspended}
                        </Pill>
                      </td>
                      <td className="py-3.5 text-right">
                        <Button
                          variant="quiet"
                          disabled={busy}
                          onClick={() =>
                            run(() =>
                              participant.active
                                ? participantsApi.deactivate(participant.id)
                                : participantsApi.activate(participant.id),
                            )
                          }
                        >
                          {participant.active ? t.suspend : t.reinstate}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <p className="mt-5 border-t border-line pt-4 text-sm text-muted">{t.suspendNote}</p>
        </Card>

        <Card title={t.addTitle} description={t.addDescription}>
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void run(async () => {
                await participantsApi.create({
                  name: name.trim(),
                  slug: slug.trim(),
                  edition,
                  defaultLocale: "fr",
                });
                setName("");
                setSlug("");
              });
            }}
          >
            <Field label={t.name} htmlFor="name">
              <input
                id="name"
                className={inputClass}
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </Field>

            <Field label={t.slug} htmlFor="slug" hint={t.slugHint}>
              <input
                id="slug"
                className={inputClass}
                value={slug}
                onChange={(e) => setSlug(e.target.value)}
                required
              />
            </Field>

            <Field label={t.sector} htmlFor="edition" hint={t.sectorHint}>
              <select
                id="edition"
                className={inputClass}
                value={edition}
                onChange={(e) => setEdition(e.target.value as Edition)}
              >
                {EDITIONS.map((option) => (
                  <option key={option} value={option}>
                    {t.editions[option]}
                  </option>
                ))}
              </select>
            </Field>

            <Button type="submit" disabled={busy}>
              {t.add}
            </Button>
            <p className="text-xs text-muted">{t.onboardingNote}</p>
          </form>
        </Card>
      </div>
    </div>
  );
}
