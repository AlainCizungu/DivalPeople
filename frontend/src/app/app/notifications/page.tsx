"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { notificationsApi, type AppNotification, type NotificationSeverity } from "@/api/client";

const SEVERITY_STYLES: Record<NotificationSeverity, string> = {
  INFO: "border-blue/40 bg-blue/5",
  WARNING: "border-warning/50 bg-warning/10",
  CRITICAL: "border-error/40 bg-error/10",
};

const SEVERITY_DOT: Record<NotificationSeverity, string> = {
  INFO: "bg-blue",
  WARNING: "bg-warning",
  CRITICAL: "bg-error",
};

export default function NotificationsPage() {
  const messages = useMessages();
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setItems(await notificationsApi.list(token));
      setError(null);
    } catch {
      setError(messages.notifications.loadFailed);
    }
  }, [token, messages.notifications.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  async function onMarkRead(id: string) {
    if (!token) return;
    await notificationsApi.markRead(id, token);
    await load();
  }

  async function onMarkAllRead() {
    if (!token) return;
    await notificationsApi.markAllRead(token);
    await load();
  }

  const unread = items?.filter((item) => !item.read).length ?? 0;

  /** Renders the message in the reader's language from the key and parameters. */
  function render(item: AppNotification): string {
    const catalogue = messages.notifications.messages as Record<string, string | undefined>;
    return interpolate(catalogue[item.messageKey], item.messageKey, item.params);
  }

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">
            {messages.notifications.title}
          </h1>
          <p className="mt-1 text-muted">{messages.notifications.subtitle}</p>
        </div>

        {unread > 0 && (
          <button
            type="button"
            onClick={() => void onMarkAllRead()}
            className="shrink-0 rounded border border-line px-3 py-2 text-sm font-semibold text-ink transition hover:border-blue hover:text-blue"
          >
            {messages.notifications.markAllRead}
          </button>
        )}
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && items === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && items?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="text-muted">{messages.notifications.empty}</p>
        </div>
      )}

      {!error && items && items.length > 0 && (
        <ul className="space-y-2.5">
          {items.map((item) => (
            <li
              key={item.id}
              className={`flex items-start gap-3 rounded-lg border p-4 ${
                item.read ? "border-line bg-white" : SEVERITY_STYLES[item.severity]
              }`}
            >
              <span
                aria-hidden="true"
                className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${
                  item.read ? "bg-line" : SEVERITY_DOT[item.severity]
                }`}
              />

              <div className="min-w-0 flex-1">
                <p className={item.read ? "text-muted" : "font-semibold text-ink"}>
                  {render(item)}
                </p>
                <p className="mt-1 text-xs text-muted">
                  {messages.notifications.severity[item.severity]} ·{" "}
                  {new Date(item.createdAt).toLocaleString()}
                </p>
              </div>

              {!item.read && (
                <button
                  type="button"
                  onClick={() => void onMarkRead(item.id)}
                  className="shrink-0 text-xs font-semibold text-blue hover:underline"
                >
                  ✓
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
