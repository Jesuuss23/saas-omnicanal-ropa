import type {Metadata} from "next";
import {Suspense} from "react";
import {Check} from "lucide-react";
import {SignupForm} from "./SignupForm";
import {loadPlans} from "@/components/marketing/Pricing";

export const metadata: Metadata = {title: "Prueba gratis", description: "Crea tu tienda y úsala 14 días gratis, sin tarjeta."};

export default async function TrialPage() {
  const plans = await loadPlans();
  return <div className="mx-auto grid max-w-6xl gap-12 px-4 py-16 sm:px-6 lg:grid-cols-[1fr_1.1fr] lg:py-24">
    <div className="grid content-start gap-6">
      <p className="text-sm font-semibold uppercase tracking-wider text-primary">Prueba gratis</p>
      <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">Crea tu tienda en 2 minutos</h1>
      <p className="text-lg text-muted-foreground">Te enviaremos un enlace a tu correo para confirmar y crear tu contraseña. Tendrás 14 días para probarlo todo.</p>
      <ul className="grid gap-3">
        {["Sin tarjeta de crédito", "Catálogo web, WhatsApp, POS y CRM desde el primer día", "Tus datos se conservan al elegir un plan"].map(t =>
          <li key={t} className="flex items-center gap-3"><span className="grid size-6 place-items-center rounded-full bg-accent text-accent-foreground"><Check className="size-3.5"/></span>{t}</li>)}
      </ul>
    </div>
    <div className="rounded-2xl border bg-card p-6 shadow-pop sm:p-8">
      <Suspense><SignupForm plans={plans.map(p => ({code: p.code, name: p.name}))}/></Suspense>
    </div>
  </div>;
}
