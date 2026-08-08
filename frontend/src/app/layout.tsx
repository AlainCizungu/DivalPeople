import type { Metadata } from "next";
import { Providers } from "@/app/providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Dival Intelligence",
  description:
    "Bilingual risk and identity intelligence for the DRC. Dival Intelligence Platform (DIP) "
    + "and the Telecom Intelligence Exchange (TIX).",
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
