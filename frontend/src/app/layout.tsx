import type { Metadata } from "next";
import { LocaleProvider } from "@/i18n/LocaleProvider";
import { AppShell } from "@/components/AppShell";
import "./globals.css";

export const metadata: Metadata = {
  title: "Dival People",
  description: "Bilingual HR, workforce intelligence, and fraud prevention on the Dival Intelligence Platform.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-soft antialiased">
        <LocaleProvider>
          <AppShell>{children}</AppShell>
        </LocaleProvider>
      </body>
    </html>
  );
}
