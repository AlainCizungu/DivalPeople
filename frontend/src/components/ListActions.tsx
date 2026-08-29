"use client";

import { useMessages } from "@/i18n/LocaleProvider";

/**
 * Download and print, on any screen that shows a list.
 *
 * <p>Two icon buttons, no explanation. A list somebody is looking at is a list they will
 * eventually want in a spreadsheet or on paper — for a credit committee, a regulator, a colleague
 * without an account — and until now the answer was to select the rows with a mouse.
 *
 * <p><strong>Built from what is already on screen, and that is the whole design.</strong> The rows
 * were fetched by a call the server already audited; turning them into a file discloses nothing
 * that was not disclosed a second earlier. An export endpoint would be a second, differently
 * shaped way to read the same records — a second place for the tenant filter to be got wrong, and
 * a second audit story to keep straight. There is no such thing here. If a future export needs to
 * reach rows the screen does not have, that is a server endpoint and it should look like one.
 *
 * <p>It follows that a download contains exactly what the reader can see, filters included. That
 * is the honest behaviour: somebody who has narrowed a list to overdue business records and presses
 * download expects those rows, not the unfiltered set.
 */
export function ListActions<T>({
  rows,
  columns,
  filename,
}: {
  /** Exactly the rows on screen, in the order they are drawn, filters already applied. */
  rows: readonly T[];
  /**
   * The columns to write, as [heading, value] pairs.
   *
   * <p>Given by the caller rather than derived from the object, because a DTO carries fields no
   * reader asked for — internal ids, the tenant's own uuid — and a CSV built by reflection would
   * put them in a file somebody emails to a bank.
   */
  columns: readonly { heading: string; value: (row: T) => string }[];
  /** Without extension. A date is appended, because a file called records.csv in a downloads
   *  folder six weeks later is a file nobody can date. */
  filename: string;
}) {
  const messages = useMessages();
  const t = messages.common;

  const disabled = rows.length === 0;

  function download() {
    const csv = [
      columns.map((column) => escapeCell(column.heading)).join(","),
      ...rows.map((row) =>
        columns.map((column) => escapeCell(column.value(row))).join(","),
      ),
    ].join("\r\n");

    // A byte order mark, and it is not superstition. Excel opens a UTF-8 CSV as the local
    // codepage unless one is present, so "Établissements Mwamba" arrives mangled — on a platform
    // whose subjects are largely French-named, that is most of the file.
    const blob = new Blob(["﻿" + csv], {
      type: "text/csv;charset=utf-8;",
    });

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${filename}-${new Date().toISOString().slice(0, 10)}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <div className="flex items-center gap-1 print:hidden">
      <IconButton label={t.download} onClick={download} disabled={disabled}>
        <path d="M12 3v12m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2" />
      </IconButton>
      <IconButton
        label={t.print}
        onClick={() => window.print()}
        disabled={disabled}
      >
        <path d="M6 9V4h12v5M6 18H4v-6h16v6h-2M8 14h8v7H8z" />
      </IconButton>
    </div>
  );
}

/**
 * Escapes one cell for CSV.
 *
 * <p>Quotes anything containing a comma, a quote or a newline, and doubles internal quotes — the
 * RFC 4180 rules. A company legal name is exactly the field that contains a comma
 * ("Trans-Congo Distribution, SARL"), and getting this wrong shifts every column after it by one
 * for that row only, which is the kind of error somebody finds after building a report on it.
 */
function escapeCell(value: string): string {
  const text = value ?? "";
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * An action with no label beside it.
 *
 * <p>The name is on the button for a screen reader and in a tooltip for everybody else. Two words
 * of chrome next to every list would cost more room than the actions are worth, and these two
 * icons are as close to universally understood as any in software.
 */
function IconButton({
  label,
  onClick,
  disabled,
  children,
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className="rounded p-2 text-muted transition hover:bg-soft hover:text-blue disabled:pointer-events-none disabled:opacity-40"
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 24 24"
        className="h-5 w-5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {children}
      </svg>
    </button>
  );
}
