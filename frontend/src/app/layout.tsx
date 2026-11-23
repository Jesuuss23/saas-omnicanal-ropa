import type {Metadata, Viewport} from "next";
import {Toaster} from "@/components/ui/toaster";
import {brand} from "@/lib/brand";
import "./globals.css";

export const metadata: Metadata = {
  title: {default: brand.name + " · " + brand.tagline, template: "%s · " + brand.name},
  description: brand.description,
  applicationName: brand.name,
};

export const viewport: Viewport = {themeColor: "#115e59"};

export default function RootLayout({children}: {children: React.ReactNode}) {
  return <html lang="es-PE"><body className="min-h-dvh">{children}<Toaster/></body></html>;
}
