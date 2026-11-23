"use client";

import {useState, type FormEvent} from "react";
import {Trash2} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input, NativeSelect} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {ApiError, superadminApi} from "@/lib/api";
import {formatDate, type TenantDetail} from "@/lib/superadmin";

/** Capacidades conocidas del catálogo de entitlements (ver saas_plan_entitlement). */
const KNOWN = [
  ["catalog.products", "Productos en catálogo"],
  ["team.users", "Usuarios del equipo"],
  ["ai.responses.monthly", "Respuestas de IA al mes"],
  ["fiscal.documents.monthly", "Comprobantes al mes"],
  ["fiscal.production", "Facturación en producción"],
  ["whatsapp.evolution", "WhatsApp por QR"],
  ["whatsapp.cloud_api", "WhatsApp Cloud API"],
  ["payments.mercadopago", "Cobros con Mercado Pago"],
  ["campaigns.email", "Campañas por email"],
  ["campaigns.whatsapp", "Campañas por WhatsApp"],
  ["domain.custom", "Dominio propio"],
] as const;

export function Overrides({id, overrides, onChange}: {id: string; overrides: TenantDetail["overrides"]; onChange: () => void}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const limit = String(f.get("limit") ?? "").trim();
    const expires = String(f.get("expiresAt") ?? "");
    setBusy(true); setError("");
    try {
      await superadminApi.json("tenants/" + id + "/entitlements/" + encodeURIComponent(String(f.get("key"))), {
        enabled: f.get("enabled") === "true", limit: limit ? Number(limit) : null,
        expiresAt: expires ? new Date(expires + "T23:59:59-05:00").toISOString() : null, reason: String(f.get("reason")).trim(),
      }, "PUT");
      toast.success("Capacidad ajustada");
      form.reset();
      onChange();
    } catch (e) { setError(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  async function remove(key: string) {
    setBusy(true);
    try {
      await superadminApi.json("tenants/" + id + "/entitlements/" + encodeURIComponent(key), undefined, "DELETE");
      toast.success("Ajuste retirado");
      onChange();
    } catch { toast.error("No se pudo retirar el ajuste."); }
    finally { setBusy(false); }
  }

  const label = (key: string) => KNOWN.find(([k]) => k === key)?.[1] ?? key;
  return <div className="grid gap-6">
    <Card><CardHeader><CardTitle>Ajustes vigentes</CardTitle>
      <CardDescription>Reemplazan lo que da el plan, solo para esta tienda.</CardDescription></CardHeader>
      <CardContent className="p-0 pt-4">
        {overrides.length ? <Table><TableHeader><TableRow><TableHead>Capacidad</TableHead><TableHead>Valor</TableHead><TableHead>Vence</TableHead><TableHead>Motivo</TableHead><TableHead/></TableRow></TableHeader>
          <TableBody>{overrides.map(o => <TableRow key={o.key}>
            <TableCell className="font-medium">{label(o.key)}<span className="block font-mono text-xs text-muted-foreground">{o.key}</span></TableCell>
            <TableCell>{o.enabled ? <Badge variant="success">Activa{o.limit !== null && " · " + o.limit}</Badge> : <Badge variant="neutral">Desactivada</Badge>}</TableCell>
            <TableCell>{o.expiresAt ? formatDate(o.expiresAt) : "Sin vencimiento"}</TableCell>
            <TableCell className="max-w-xs text-muted-foreground">{o.reason}</TableCell>
            <TableCell className="text-right"><Button variant="ghost" size="icon" aria-label={"Retirar " + label(o.key)} disabled={busy} onClick={() => remove(o.key)}><Trash2/></Button></TableCell>
          </TableRow>)}</TableBody></Table>
          : <p className="px-6 pb-6 text-sm text-muted-foreground">La tienda usa exactamente lo que incluye su plan.</p>}
      </CardContent></Card>

    <Card><CardHeader><CardTitle>Nuevo ajuste</CardTitle></CardHeader><CardContent>
      <form onSubmit={save} className="grid gap-4 md:grid-cols-2">
        <Field label="Capacidad" htmlFor="ov-key"><NativeSelect id="ov-key" name="key" required>
          {KNOWN.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</NativeSelect></Field>
        <Field label="Estado" htmlFor="ov-enabled"><NativeSelect id="ov-enabled" name="enabled" defaultValue="true">
          <option value="true">Activa</option><option value="false">Desactivada</option></NativeSelect></Field>
        <Field label="Límite" htmlFor="ov-limit" hint="Vacío = sin límite."><Input id="ov-limit" name="limit" type="number" min={0}/></Field>
        <Field label="Vence" htmlFor="ov-exp" hint="Opcional."><Input id="ov-exp" name="expiresAt" type="date"/></Field>
        <Field label="Motivo" htmlFor="ov-reason" className="md:col-span-2"><Input id="ov-reason" name="reason" maxLength={200} required placeholder="Ej.: piloto con 500 productos"/></Field>
        {error && <Alert variant="destructive" className="md:col-span-2">{error}</Alert>}
        <div className="md:col-span-2"><Button type="submit" loading={busy}>Guardar ajuste</Button></div>
      </form>
    </CardContent></Card>
  </div>;
}
