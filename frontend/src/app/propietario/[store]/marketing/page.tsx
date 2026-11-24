"use client";

import {useState, type FormEvent} from "react";
import {Mail, MessageCircle, Megaphone, ShoppingCart, UserX} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {StatCard} from "@/components/console/StatCard";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {formatDate, limaDateTime} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {useStore} from "../_panel/StoreContext";

type Channel = "EMAIL" | "WHATSAPP";
type Segment = {buyers: "ANY" | "BUYERS" | "NON_BUYERS"; lastOrderWithinDays: number | null; noOrderForDays: number | null; minSpent: number | null};
type Stats = {total: number; pending: number; sent: number; failed: number; skipped: number};
type Campaign = {id: string; name: string; channel: Channel; segment: Segment; subject: string | null; body: string | null;
  templateName: string | null; templateLang: string | null; status: "DRAFT" | "SENDING" | "SENT" | "CANCELLED";
  createdBy: string; createdAt: string; startedAt: string | null; finishedAt: string | null; revision: number; stats: Stats};
type ChannelState = {included: boolean; monthlyLimit: number | null; usedThisMonth: number};
type Template = {name: string; language: string; status: string; componentsJson: string | null};
type Abandoned = {enabled: boolean; delayMinutes: number; templateName: string | null; templateLang: string | null; revision: number};
type Overview = {audience: {email: number; whatsapp: number; unsubscribed: number}; email: ChannelState; whatsapp: ChannelState;
  cloudApiReady: boolean; templates: Template[]; abandoned: Abandoned; reminders: {sent: number; failed: number}; campaigns: Campaign[]};

const statusLabel: Record<Campaign["status"], [string, "neutral" | "warning" | "success" | "destructive"]> = {
  DRAFT: ["Borrador", "neutral"], SENDING: ["Enviando", "warning"], SENT: ["Enviada", "success"], CANCELLED: ["Cancelada", "destructive"],
};

export default function MarketingPage() {
  const {store} = useStore();
  const [refresh, setRefresh] = useState(0);
  const overview = useApiData<Overview>(ownerApi.url("stores/" + store + "/marketing"), refresh);
  const reload = () => setRefresh(r => r + 1);
  const data = overview.data;

  return <>
    <PageHeader title="Marketing" description="Campañas por correo y WhatsApp solo a clientes que aceptaron recibir novedades, y recordatorio de carrito abandonado."/>
    {overview.error && <Alert variant="destructive">{overview.error.message}</Alert>}
    {!data ? <div className="grid gap-4 sm:grid-cols-3">{[0, 1, 2].map(i => <Skeleton key={i} className="h-28"/>)}</div> : <div className="grid gap-6">
      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard label="Aceptan correos" value={data.audience.email} icon={Mail} hint={usage(data.email, "correos")}/>
        <StatCard label="Aceptan WhatsApp" value={data.audience.whatsapp} icon={MessageCircle} hint={usage(data.whatsapp, "mensajes")}/>
        <StatCard label="Se dieron de baja" value={data.audience.unsubscribed} icon={UserX} hint="Nunca reciben campañas"/>
      </div>
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_24rem]">
        <NewCampaign store={store} overview={data} onCreated={reload}/>
        <AbandonedCart key={data.abandoned.revision} store={store} overview={data} onSaved={reload}/>
      </div>
      <Campaigns store={store} campaigns={data.campaigns} onChanged={reload}/>
    </div>}
  </>;
}

function usage(state: ChannelState, unit: string) {
  if (!state.included) return "Tu plan no incluye este canal";
  return state.monthlyLimit === null ? state.usedThisMonth + " " + unit + " este mes" : state.usedThisMonth + " de " + state.monthlyLimit + " " + unit + " este mes";
}

function templateKey(t: {name: string; language: string}) {
  return t.name + "|" + t.language;
}

function NewCampaign({store, overview, onCreated}: {store: string; overview: Overview; onCreated: () => void}) {
  const [channel, setChannel] = useState<Channel>("EMAIL");
  const [name, setName] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [template, setTemplate] = useState("");
  const [buyers, setBuyers] = useState<Segment["buyers"]>("ANY");
  const [recent, setRecent] = useState("");
  const [inactive, setInactive] = useState("");
  const [minSpent, setMinSpent] = useState("");
  const [count, setCount] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const state = channel === "EMAIL" ? overview.email : overview.whatsapp;

  const segment = (): Segment => ({buyers, lastOrderWithinDays: recent ? Number(recent) : null,
    noOrderForDays: inactive ? Number(inactive) : null, minSpent: minSpent ? Number(minSpent) : null});
  const changed = <T,>(set: (v: T) => void) => (v: T) => { set(v); setCount(null); };

  async function preview() {
    try {
      const r = await ownerApi.json<{recipients: number}>("stores/" + store + "/marketing/preview", {channel, segment: segment()});
      setCount(r.recipients);
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo calcular."); }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    const [templateName, templateLang] = template ? template.split("|") : [null, null];
    setBusy(true);
    try {
      await ownerApi.json("stores/" + store + "/marketing/campaigns", {name, channel, segment: segment(), revision: 0,
        subject: channel === "EMAIL" ? subject : null, body: channel === "EMAIL" ? body : null,
        templateName: channel === "WHATSAPP" ? templateName : null, templateLang: channel === "WHATSAPP" ? templateLang : null});
      toast.success("Borrador guardado. Revísalo y lánzalo desde la lista.");
      setName(""); setSubject(""); setBody(""); setCount(null);
      onCreated();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  return <Card><form onSubmit={save}>
    <CardHeader><CardTitle className="flex items-center gap-2"><Megaphone className="size-5 text-primary"/>Nueva campaña</CardTitle>
      <CardDescription>Se guarda como borrador. Al lanzarla se fija la lista de destinatarios de ese momento.</CardDescription></CardHeader>
    <CardContent className="grid gap-5">
      <div role="radiogroup" aria-label="Canal" className="grid grid-cols-2 gap-2 sm:max-w-sm">
        {(["EMAIL", "WHATSAPP"] as Channel[]).map(c => <label key={c} className="flex cursor-pointer items-center gap-2 rounded-lg border p-3 text-sm has-[:checked]:border-primary has-[:checked]:bg-accent">
          <input type="radio" name="channel" value={c} checked={channel === c} onChange={() => changed(setChannel)(c)} className="accent-[var(--primary)]"/>
          {c === "EMAIL" ? <><Mail className="size-4"/>Correo</> : <><MessageCircle className="size-4"/>WhatsApp</>}</label>)}
      </div>
      {!state.included && <Alert>Tu plan actual no incluye campañas por {channel === "EMAIL" ? "correo" : "WhatsApp"}. Puedes preparar el borrador y lanzarlo cuando cambies de plan.</Alert>}
      <Field label="Nombre interno" htmlFor="mk-name"><Input id="mk-name" value={name} onChange={e => setName(e.target.value)} maxLength={120} required placeholder="Nueva colección de verano"/></Field>
      {channel === "EMAIL" ? <>
        <Field label="Asunto" htmlFor="mk-subject"><Input id="mk-subject" value={subject} onChange={e => setSubject(e.target.value)} maxLength={150} required/></Field>
        <Field label="Mensaje" htmlFor="mk-body" hint="Texto simple. Agregamos tu nombre de tienda y el enlace para darse de baja.">
          <textarea id="mk-body" value={body} onChange={e => setBody(e.target.value)} maxLength={5000} rows={6} required
            className="rounded-lg border border-input bg-background px-3 py-2 text-sm"/></Field>
      </> : !overview.cloudApiReady ? <Alert>Conecta WhatsApp Cloud API en la sección WhatsApp para enviar plantillas aprobadas por Meta.</Alert>
        : <Field label="Plantilla aprobada" htmlFor="mk-template" hint="Meta exige plantillas para escribir primero. Si tiene {{1}}, lo llenamos con el nombre del cliente.">
          <select id="mk-template" value={template} onChange={e => setTemplate(e.target.value)} required className="h-9 rounded-lg border border-input bg-background px-3 text-sm">
            <option value="">Elige una plantilla</option>
            {overview.templates.map(t => <option key={templateKey(t)} value={templateKey(t)}>{t.name} ({t.language})</option>)}
          </select></Field>}
      <fieldset className="grid gap-3 rounded-lg border p-4">
        <legend className="px-1 text-sm font-medium">Segmento</legend>
        <Field label="Clientes" htmlFor="mk-buyers">
          <select id="mk-buyers" value={buyers} onChange={e => changed(setBuyers)(e.target.value as Segment["buyers"])} className="h-9 rounded-lg border border-input bg-background px-3 text-sm">
            <option value="ANY">Todos los que aceptaron</option><option value="BUYERS">Ya compraron</option><option value="NON_BUYERS">Aún no compran</option>
          </select></Field>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Compró en los últimos (días)" htmlFor="mk-recent"><Input id="mk-recent" type="number" min={1} max={3650} value={recent} onChange={e => changed(setRecent)(e.target.value)}/></Field>
          <Field label="Sin comprar hace (días)" htmlFor="mk-inactive"><Input id="mk-inactive" type="number" min={1} max={3650} value={inactive} onChange={e => changed(setInactive)(e.target.value)}/></Field>
          <Field label="Gastó al menos (S/)" htmlFor="mk-spent"><Input id="mk-spent" type="number" min={1} step="0.01" value={minSpent} onChange={e => changed(setMinSpent)(e.target.value)}/></Field>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Button type="button" variant="outline" onClick={preview}>Calcular destinatarios</Button>
          {count !== null && <p className="text-sm" aria-live="polite"><strong>{count}</strong> {count === 1 ? "contacto recibiría" : "contactos recibirían"} esta campaña.</p>}
        </div>
      </fieldset>
      <div><Button type="submit" loading={busy} disabled={channel === "WHATSAPP" && !overview.cloudApiReady}>Guardar borrador</Button></div>
    </CardContent>
  </form></Card>;
}

function AbandonedCart({store, overview, onSaved}: {store: string; overview: Overview; onSaved: () => void}) {
  const initial = overview.abandoned;
  const [enabled, setEnabled] = useState(initial.enabled);
  const [delay, setDelay] = useState(String(initial.delayMinutes));
  const [template, setTemplate] = useState(initial.templateName ? initial.templateName + "|" + initial.templateLang : "");
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    const [templateName, templateLang] = template ? template.split("|") : [null, null];
    setBusy(true);
    try {
      await ownerApi.json("stores/" + store + "/marketing/abandoned-cart",
        {enabled, delayMinutes: Number(delay), templateName, templateLang, revision: initial.revision}, "PUT");
      toast.success(enabled ? "Recordatorio activado" : "Configuración guardada");
      onSaved();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  return <Card><form onSubmit={save}>
    <CardHeader><CardTitle className="flex items-center gap-2"><ShoppingCart className="size-5 text-primary"/>Carrito abandonado</CardTitle>
      <CardDescription>Un solo WhatsApp a quien dejó sus datos, aceptó novedades y no terminó la compra.</CardDescription></CardHeader>
    <CardContent className="grid gap-4">
      {!overview.cloudApiReady && <Alert>Necesita WhatsApp Cloud API conectado y una plantilla aprobada.</Alert>}
      <label className="flex items-start gap-3 rounded-lg border p-3 text-sm">
        <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} className="mt-0.5 size-4 accent-[var(--primary)]"/>
        <span>Enviar recordatorio automático</span></label>
      <Field label="Esperar (minutos)" htmlFor="ab-delay" hint="De 15 a 1440. Nunca después de 24 horas.">
        <Input id="ab-delay" type="number" min={15} max={1440} value={delay} onChange={e => setDelay(e.target.value)} required className="max-w-32"/></Field>
      <Field label="Plantilla" htmlFor="ab-template">
        <select id="ab-template" value={template} onChange={e => setTemplate(e.target.value)} required={enabled} className="h-9 rounded-lg border border-input bg-background px-3 text-sm">
          <option value="">Sin plantilla</option>
          {overview.templates.map(t => <option key={templateKey(t)} value={templateKey(t)}>{t.name} ({t.language})</option>)}
        </select></Field>
      <p className="text-xs text-muted-foreground">Últimos 30 días: {overview.reminders.sent} enviados, {overview.reminders.failed} fallidos.</p>
      <div><Button type="submit" variant="outline" loading={busy}>Guardar</Button></div>
    </CardContent>
  </form></Card>;
}

function Campaigns({store, campaigns, onChanged}: {store: string; campaigns: Campaign[]; onChanged: () => void}) {
  const [busy, setBusy] = useState<string | null>(null);

  async function act(c: Campaign, action: "launch" | "cancel") {
    if (action === "launch" && !window.confirm("¿Lanzar \"" + c.name + "\"? Se enviará a los contactos del segmento con consentimiento.")) return;
    setBusy(c.id);
    try {
      await ownerApi.json("stores/" + store + "/marketing/campaigns/" + c.id + "/" + action, action === "launch" ? {revision: c.revision} : {});
      toast.success(action === "launch" ? "Campaña en envío" : "Campaña cancelada");
      onChanged();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo completar."); }
    finally { setBusy(null); }
  }

  return <Card>
    <CardHeader className="flex-row items-center justify-between gap-4"><div className="grid gap-1"><CardTitle>Campañas</CardTitle>
      <CardDescription>Los envíos salen en lotes; actualiza para ver el avance.</CardDescription></div>
      <Button variant="outline" size="sm" onClick={onChanged}>Actualizar</Button></CardHeader>
    <CardContent>
      {!campaigns.length ? <EmptyState icon={Megaphone} title="Aún no hay campañas" description="Crea un borrador arriba para empezar."/> :
        <div className="overflow-x-auto"><Table>
          <TableHeader><TableRow><TableHead>Campaña</TableHead><TableHead>Canal</TableHead><TableHead>Estado</TableHead>
            <TableHead className="text-right">Enviados</TableHead><TableHead>Fecha</TableHead><TableHead/></TableRow></TableHeader>
          <TableBody>{campaigns.map(c => {
            const [label, variant] = statusLabel[c.status];
            return <TableRow key={c.id}>
              <TableCell className="max-w-64"><p className="truncate font-medium">{c.name}</p>
                <p className="truncate text-xs text-muted-foreground">{c.channel === "EMAIL" ? c.subject : c.templateName}</p></TableCell>
              <TableCell>{c.channel === "EMAIL" ? "Correo" : "WhatsApp"}</TableCell>
              <TableCell><Badge variant={variant}>{label}</Badge></TableCell>
              <TableCell className="text-right tabular-nums">{c.status === "DRAFT" ? "—" : c.stats.sent + " / " + c.stats.total}
                {(c.stats.failed > 0 || c.stats.skipped > 0) && <p className="text-xs text-muted-foreground">{c.stats.failed} fallidos · {c.stats.skipped} omitidos</p>}</TableCell>
              <TableCell className="whitespace-nowrap text-sm text-muted-foreground">{formatDate(c.startedAt ?? c.createdAt, limaDateTime)}</TableCell>
              <TableCell className="whitespace-nowrap text-right">
                {c.status === "DRAFT" && <Button size="sm" loading={busy === c.id} onClick={() => act(c, "launch")}>Lanzar</Button>}
                {(c.status === "DRAFT" || c.status === "SENDING") && <Button size="sm" variant="ghost" disabled={busy === c.id} onClick={() => act(c, "cancel")}>Cancelar</Button>}
              </TableCell>
            </TableRow>;
          })}</TableBody>
        </Table></div>}
    </CardContent>
  </Card>;
}
