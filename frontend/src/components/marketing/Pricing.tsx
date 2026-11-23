import Link from "next/link";
import {Check, Minus, Sparkles} from "lucide-react";
import {Button} from "@/components/ui/button";
import {backendBase} from "@/lib/api";
import {cn} from "@/lib/utils";
import {entitlementLabels, entitlementValue, featuredPlan, planPrice, planTaglines, type Plan} from "@/lib/plans";

/** Planes públicos desde el backend (cacheados 5 minutos). Devuelve [] si el backend no responde. */
export async function loadPlans(): Promise<Plan[]> {
  try {
    const res = await fetch(backendBase() + "/public/plans", {next: {revalidate: 300}});
    return res.ok ? await res.json() : [];
  } catch {
    return [];
  }
}

const highlights = ["catalog.products", "team.users", "whatsapp.evolution", "whatsapp.cloud_api", "ai.responses.monthly", "fiscal.documents.monthly", "domain.custom", "campaigns.whatsapp"];

export function PricingCards({plans}: {plans: Plan[]}) {
  if (!plans.length) return <p className="rounded-xl border bg-card p-6 text-center text-muted-foreground">
    No pudimos cargar los planes en este momento. <Link className="font-medium text-primary underline" href="/prueba-gratis">Empieza tu prueba gratis</Link> y elige tu plan después.
  </p>;
  return <div className="grid gap-6 lg:grid-cols-3">
    {plans.map(plan => {
      const featured = plan.code === featuredPlan;
      return <article key={plan.code} className={cn("relative flex flex-col rounded-2xl border bg-card p-7 shadow-card",
        featured && "border-primary shadow-pop ring-1 ring-primary lg:-my-3 lg:py-10")}>
        {featured && <span className="absolute -top-3 left-7 inline-flex items-center gap-1 rounded-full bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground"><Sparkles className="size-3"/>Más elegido</span>}
        <h3 className="text-lg font-semibold">{plan.name}</h3>
        <p className="mt-1 min-h-10 text-sm text-muted-foreground">{planTaglines[plan.code] ?? ""}</p>
        <p className="mt-6 flex items-baseline gap-1"><span className="text-4xl font-semibold tracking-tight">{planPrice(plan)}</span><span className="text-sm text-muted-foreground">/ mes</span></p>
        <Button asChild size="lg" variant={featured ? "default" : "outline"} className="mt-6">
          <Link href={"/prueba-gratis?plan=" + plan.code}>Probar 14 días gratis</Link>
        </Button>
        <ul className="mt-7 grid gap-3 text-sm">
          {entitlementLabels.filter(e => highlights.includes(e.key)).map(e => {
            const value = entitlementValue(plan, e.key);
            return <li key={e.key} className={cn("flex items-start gap-2.5", !value && "text-muted-foreground/70")}>
              {value ? <Check className="mt-0.5 size-4 shrink-0 text-primary"/> : <Minus className="mt-0.5 size-4 shrink-0"/>}
              <span>{e.label}{value && value !== "Incluido" && <> · <strong className="font-medium">{value}</strong>{e.unit && value !== "Ilimitado" ? " " + e.unit : ""}</>}</span>
            </li>;
          })}
        </ul>
      </article>;
    })}
  </div>;
}

export function PricingTable({plans}: {plans: Plan[]}) {
  if (!plans.length) return null;
  return <div className="overflow-x-auto rounded-2xl border bg-card shadow-card">
    <table className="w-full min-w-[640px] text-sm">
      <thead><tr className="border-b">
        <th className="p-4 text-left font-medium text-muted-foreground">Compara los planes</th>
        {plans.map(p => <th key={p.code} className={cn("p-4 text-left font-semibold", p.code === featuredPlan && "text-primary")}>{p.name}<br/><span className="font-normal text-muted-foreground">{planPrice(p)} / mes</span></th>)}
      </tr></thead>
      <tbody>{entitlementLabels.map(e => <tr key={e.key} className="border-b last:border-0">
        <th scope="row" className="p-4 text-left font-normal">{e.label}{e.unit && <span className="text-muted-foreground"> ({e.unit})</span>}</th>
        {plans.map(p => {
          const value = entitlementValue(p, e.key);
          return <td key={p.code} className="p-4">{value === null ? <Minus className="size-4 text-muted-foreground/60" aria-label="No incluido"/>
            : value === "Incluido" ? <Check className="size-4 text-primary" aria-label="Incluido"/> : value}</td>;
        })}
      </tr>)}</tbody>
    </table>
  </div>;
}
