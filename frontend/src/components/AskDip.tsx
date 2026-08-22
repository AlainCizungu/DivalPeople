"use client";

import { useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { ApiError, analystApi, type AskAnswer } from "@/api/client";
import { AskDipMark } from "@/components/ask/AskDipMark";
import { QuestionField } from "@/components/ask/QuestionField";
import { Button, Card, EmptyState, ErrorNotice, Pill } from "@/components/ui";

/**
 * Ask DIP.
 *
 * <p><strong>The model reads the question; the platform computes the answer.</strong> Every figure
 * below is a sum or a count over rows the caller is already entitled to. Nothing on this screen was
 * produced by a language model except the sentence explicitly labelled as such — and that sentence
 * is decoration over numbers that were already correct before it was written.
 *
 * <p>That division is why the interpretation is printed above the answer. The failure mode of a
 * natural-language front end is not a wrong number, it is a right number to a different question,
 * and a reader who can see what was understood catches that in a second.
 *
 * <p>Screening across institutions is quoted and not spent. Knowing how many institutions report a
 * company is exactly what an inquiry discloses, so doing it to forty companies is forty inquiries
 * against the same hourly allowance as everybody else. The button carries the price.
 */
export function AskDip({ bare = false }: { bare?: boolean } = {}) {
  const messages = useMessages();
  const t = messages.ask;

  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<AskAnswer | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function run(text: string) {
    setBusy(true);
    setFailure(null);
    try {
      setAnswer(await analystApi.ask(text));
    } catch (caught) {
      setFailure(
        caught instanceof ApiError
          ? `${caught.status} ${caught.code} — ${caught.message}`
          : String(caught),
      );
    } finally {
      setBusy(false);
    }
  }

  const suggestions: string[] = t.suggestions;

  const body = (
    <>
      <form
        className="flex flex-col gap-3 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          void run(question.trim());
        }}
      >
        <QuestionField
          value={question}
          onChange={setQuestion}
          placeholder={t.placeholder}
          label={t.title}
          disabled={busy}
        />
        <Button type="submit" disabled={busy || question.trim().length === 0}>
          {busy ? t.thinking : t.askButton}
        </Button>
      </form>

      {/* The closed set, offered as sentences. A user who can see what the analyst answers stops
          guessing at phrasings, and the list is short because the list of intents is short. */}
      <div className="mt-3 flex flex-wrap gap-2">
        {suggestions.map((suggestion) => (
          <button
            key={suggestion}
            type="button"
            className="rounded-full border border-line bg-white px-3 py-1.5 text-xs text-muted transition hover:-translate-y-0.5 hover:border-blue hover:text-blue hover:shadow-sm"
            onClick={() => {
              setQuestion(suggestion);
              void run(suggestion);
            }}
          >
            {suggestion}
          </button>
        ))}
      </div>

      {/* While the answer is being assembled. The mark quickens and three lines stand in for the
          shape of what is coming — a spinner says "wait", this says "an answer is being built".
          Nothing here is a number: a skeleton that showed digits would be showing invented ones. */}
      {busy && (
        <div className="mt-5 flex items-start gap-3 rounded-lg border border-line bg-soft/60 p-4">
          <AskDipMark size={28} busy />
          <div className="flex-1 space-y-2 pt-1">
            <span className="block h-2.5 w-1/3 rounded-full bg-line" />
            <span className="block h-2.5 w-2/3 rounded-full bg-line" />
            <span className="block h-2.5 w-1/2 rounded-full bg-line" />
          </div>
        </div>
      )}

      {/* Before anything has been asked. An empty panel reads as broken; this reads as ready, and
          it repeats the one sentence that governs the whole feature. */}
      {!busy && !answer && !failure && (
        <div className="mt-5 flex items-start gap-3 rounded-lg border border-dashed border-line px-4 py-5">
          <AskDipMark size={28} />
          <p className="pt-1 text-sm text-muted">{t.idle}</p>
        </div>
      )}

      {failure && (
        <ErrorNotice>
          {t.failed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}

      {answer && <AnswerView answer={answer} t={t} />}
    </>
  );

  // In the floating panel the heading and border come from the panel, so a Card would draw a box
  // inside a box. On the page it supplies both.
  return bare ? (
    <div className="p-4">
      <p className="mb-3 text-xs text-muted">{t.note}</p>
      {body}
    </div>
  ) : (
    <Card>
      {/* The heading is drawn here rather than passed to Card so the mark can sit in it. On the
          full page this panel is the whole screen, and a mark beside the title is what tells
          somebody at a glance which of the platform's boxes this one is. */}
      <div className="mb-4 flex items-start gap-3">
        <AskDipMark size={36} busy={busy} />
        <div>
          <h2 className="font-bold text-navy">{t.title}</h2>
          <p className="mt-0.5 text-sm text-muted">{t.note}</p>
        </div>
      </div>
      {body}
    </Card>
  );
}

/**
 * A caption for a figure, falling back to the code the server sent.
 *
 * <p>The codes are server-side and cannot be typed against the catalogue without duplicating them
 * in TypeScript and letting the two drift. The fallback is the point rather than a concession: a
 * figure added to the API before the translations catch up renders as its raw code, which is ugly
 * and visible. Rendering an empty cell would hide it.
 */
function label(table: Record<string, string>, code: string): string {
  return table[code] ?? code;
}

function AnswerView({
  answer,
  t,
}: {
  answer: AskAnswer;
  t: ReturnType<typeof useMessages>["ask"];
}) {
  if (answer.understood.intent === "UNSUPPORTED") {
    return (
      <div className="mt-5">
        <EmptyState>{t.unsupported}</EmptyState>
      </div>
    );
  }

  return (
    <div className="mt-5 border-t border-line pt-4">
      {/* Before the figures, never after. */}
      <div className="mb-3 flex flex-wrap items-center gap-2 text-xs">
        <Pill tone="neutral">{t.intents[answer.understood.intent]}</Pill>
        {answer.understood.minAmount && (
          <span className="text-muted">
            {interpolate(t.readAmount, t.readAmount, { amount: answer.understood.minAmount })}
          </span>
        )}
        {answer.understood.days > 0 && (
          <span className="text-muted">
            {interpolate(t.readWindow, t.readWindow, {
              days: String(answer.understood.days),
            })}
          </span>
        )}
        <span className="text-muted">
          {answer.understood.byModel ? t.readByModel : t.readByRules}
        </span>
      </div>

      {answer.narrative && (
        <p className="mb-4 rounded border border-line bg-soft px-4 py-3 text-sm">
          {answer.narrative}
          {/* Labelled, always. A generated sentence that is not marked as generated is the one
              thing this screen must never ship. */}
          <span className="mt-1 block text-xs text-muted">
            {interpolate(t.writtenBy, t.writtenBy, { model: answer.narratedBy ?? "" })}
          </span>
        </p>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        {answer.figures.map((figure) => (
          <div key={figure.code} className="rounded-lg border border-line bg-white p-4">
            <p className="text-xs text-muted">{label(t.figures, figure.code)}</p>
            <p className="mt-1 text-2xl font-bold tabular-nums text-navy">
              {figure.value}
              {figure.unit ? ` ${figure.unit}` : ""}
            </p>
          </div>
        ))}
      </div>

      <p className="mt-3 text-xs text-muted">{t.yourExposureNote}</p>

      {answer.inquiryCost > 0 && (
        <p className="mt-3 rounded border border-warning/50 bg-warning/10 px-4 py-3 text-sm text-[#7c4a03]">
          {interpolate(t.inquiryCost, t.inquiryCost, {
            count: String(answer.inquiryCost),
          })}
        </p>
      )}

      {/* What the answer deliberately does not contain. Listed rather than omitted: a summary
          that quietly leaves out what it could not find reads as complete, and this one can be
          quieter than the registry when a company is contested. */}
      {answer.caveats.length > 0 && (
        <ul className="mt-4 flex flex-col gap-2 text-xs">
          {answer.caveats.map((code) => (
            <li key={code} className="rounded border border-line bg-soft px-3 py-2 text-muted">
              {label(t.caveats, code)}
            </li>
          ))}
        </ul>
      )}

      {answer.figures.length === 0 && answer.understood.intent === "WHY_RISKY" && (
        <EmptyState>{t.noCompany}</EmptyState>
      )}

      {answer.companies.length > 0 && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[32rem] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <th className="pb-1.5 font-semibold">{t.colCompany}</th>
                <th className="pb-1.5 font-semibold">{t.colOwed}</th>
                <th className="pb-1.5 font-semibold">{t.colOldest}</th>
              </tr>
            </thead>
            <tbody>
              {answer.companies.map((company) => (
                <tr key={company.subjectId} className="border-b border-line/60 last:border-0">
                  <td className="py-2 pr-3">{company.name}</td>
                  <td className="py-2 pr-3 tabular-nums">{company.owed}</td>
                  <td className="py-2 pr-3 tabular-nums text-muted">
                    {interpolate(t.days, t.days, { days: String(company.oldestDays) })}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
