import type { Metadata } from "next";
import { Providers } from "@/app/providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Dival People",
  description:
    "Bilingual HR, workforce intelligence, and fraud prevention on the Dival Intelligence Platform.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-soft antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
