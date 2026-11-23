"use client";

import {useState, type FormEvent} from "react";
import {Link2} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {ApiError, superadminApi} from "@/lib/api";
import {type PlanRow} from "@/lib/superadmin";
import {money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

export default function Plans() {
  const {data, error, reload} = useApiData<PlanRow[]>(superadminApi.url("plans"));
  return <>
    <PageHeader title="Planes" description="Precios de lista y vínculo con los planes recurrentes de Culqi. Un plan público sin Culqi no se puede pagar en línea."/>
    {error && <Alert variant="destructive">{error.message}</Alert>}
    <Card className="p-0">
      {!data ? <div className="grid gap-2 p-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-12"/>)}</div>
        : <Table><TableHeader><TableRow><TableHead>Plan</TableHead><TableHead>Precio mensual</TableHead><TableHead>Visibilidad</TableHead><TableHead>Plan en Culqi</TableHead></TableRow></TableHeader>
          <TableBody>{data.map(p => <TableRow key={p.code}>
            <TableCell><span className="font-medium">{p.name}</span><span className="block font-mono text-xs text-muted-foreground">{p.code}</span></TableCell>
            <TableCell className="tabular-nums">{p.priceCents ? money.format(p.priceCents / 100) : "Gratis"}</TableCell>
            <TableCell>{p.publicPlan ? <Badge>Público</Badge> : <Badge variant="neutral">Interno</Badge>}</TableCell>
            <TableCell>{p.priceCents > 0 ? <CulqiLink plan={p} onSaved={reload}/> : <span className="text-muted-foreground">No aplica</span>}</TableCell>
          </TableRow>)}</TableBody></Table>}
    </Card>
  </>;
}

function CulqiLink({plan, onSaved}: {plan: PlanRow; onSaved: () => void}) {
  const [value, setValue] = useState(plan.culqiPlanId ?? "");
  const [busy, setBusy] = useState(false);
  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await superadminApi.json("plans/" + plan.code + "/culqi", {culqiPlanId: value.trim()}, "PUT");
      toast.success("Plan " + plan.name + " vinculado a Culqi");
      onSaved();
    } catch (e) { toast.error(e instanceof ApiError && e.status === 400 ? "El identificador debe empezar con pln_." : "No se pudo guardar."); }
    finally { setBusy(false); }
  }
  return <form onSubmit={save} className="flex max-w-md gap-2">
    <Input aria-label={"Plan de Culqi para " + plan.name} placeholder="pln_live_…" value={value} onChange={e => setValue(e.target.value)} pattern="pln_[A-Za-z0-9_]{4,60}" required className="font-mono"/>
    <Button type="submit" variant="outline" loading={busy} disabled={value.trim() === (plan.culqiPlanId ?? "")}><Link2/>Guardar</Button>
  </form>;
}
