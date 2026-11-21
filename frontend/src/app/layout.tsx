import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Omnicanal · Administración",
  description: "Administración de la plataforma de comercio omnicanal",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return <html lang="es"><body>{children}</body></html>;
}
