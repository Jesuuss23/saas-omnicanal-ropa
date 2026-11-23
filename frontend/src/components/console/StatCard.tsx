import type {LucideIcon} from "lucide-react";
import {Card} from "@/components/ui/card";
import {cn} from "@/lib/utils";

/** Cifra principal con etiqueta y una línea de contexto. */
export function StatCard({label, value, hint, icon: Icon, tone = "default"}: {
  label: string; value: React.ReactNode; hint?: React.ReactNode; icon?: LucideIcon; tone?: "default" | "warning" | "destructive";
}) {
  return <Card className="grid gap-3 p-5">
    <div className="flex items-center justify-between gap-2">
      <p className="text-sm font-medium text-muted-foreground">{label}</p>
      {Icon && <span className={cn("grid size-8 place-items-center rounded-lg",
        tone === "warning" ? "bg-warning/15 text-warning-foreground" : tone === "destructive" ? "bg-destructive/10 text-destructive" : "bg-accent text-accent-foreground")}>
        <Icon className="size-4" aria-hidden/></span>}
    </div>
    <p className="text-3xl font-semibold tabular-nums tracking-tight">{value}</p>
    {hint && <p className="text-sm text-muted-foreground">{hint}</p>}
  </Card>;
}
