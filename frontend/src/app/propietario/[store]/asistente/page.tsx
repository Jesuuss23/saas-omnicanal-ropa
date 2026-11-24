"use client";

import {useState, type FormEvent} from "react";
import {Bot, CircleAlert, Send, Sparkles, Wrench} from "lucide-react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {cn} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import {useStore} from "../_panel/StoreContext";

type Settings = {enabled: boolean; instructions: string; slaMinutes: number; revision: number};
type Status = {configured: boolean; platformEnabled: boolean; planIncluded: boolean; usedThisMonth: number; monthlyLimit: number | null};
type Answer = {text: string; escalate: boolean; reason: string | null; summary: string | null; toolsUsed: string[]};
type Turn = {author: "CUSTOMER" | "AI"; text: string; note?: string};

const toolLabels: Record<string, string> = {buscarPrendas: "consultó el catálogo", infoTienda: "consultó recojo y delivery", escalar: "pidió pasar a una persona"};

export default function AssistantPage() {
  const {store} = useStore();
  const base = "stores/" + store + "/assistant";
  const settings = useApiData<Settings>(ownerApi.url(base + "/settings"));
  const status = useApiData<Status>(ownerApi.url(base + "/status"), settings.data?.revision);

  return <>
    <PageHeader title="Asistente IA" description="Una vendedora que responde en WhatsApp con tu catálogo real: precios, tallas, colores y stock. Pasa a tu equipo lo que no puede resolver."/>
    {settings.error && <Alert variant="destructive">{settings.error.message}</Alert>}
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_24rem]">
      <div className="grid content-start gap-6">
        {status.data && <StatusCard status={status.data} enabled={settings.data?.enabled ?? false}/>}
        {!settings.data ? <Skeleton className="h-80"/> : <SettingsForm key={settings.data.revision} store={store} initial={settings.data} onSaved={settings.mutate}/>}
      </div>
      <Tester store={store} configured={status.data?.configured ?? true}/>
    </div>
  </>;
}

function StatusCard({status, enabled}: {status: Status; enabled: boolean}) {
  const problems = [
    !status.configured && "La IA todavía no está configurada en el servidor (falta la clave de Gemini).",
    !status.platformEnabled && "La IA está pausada para todas las tiendas por mantenimiento.",
    !status.planIncluded && "Tu plan actual no incluye respuestas con IA.",
  ].filter(Boolean) as string[];
  const pct = status.monthlyLimit ? Math.min(100, Math.round(status.usedThisMonth / status.monthlyLimit * 100)) : 0;
  return <Card>
    <CardHeader className="flex-row items-start justify-between gap-4">
      <div className="grid gap-1"><CardTitle className="flex items-center gap-2"><Bot className="size-5 text-primary"/>Estado</CardTitle>
        <CardDescription>{status.monthlyLimit === null ? status.usedThisMonth + " respuestas este mes (sin límite)" : status.usedThisMonth + " de " + status.monthlyLimit + " respuestas del plan este mes"}</CardDescription></div>
      <Badge variant={enabled && !problems.length ? "success" : "neutral"}>{enabled && !problems.length ? "Respondiendo" : "Apagada"}</Badge>
    </CardHeader>
    <CardContent className="grid gap-3">
      {status.monthlyLimit !== null && <div className="h-2 rounded-full bg-muted" role="progressbar" aria-label="Respuestas usadas este mes"
        aria-valuemin={0} aria-valuemax={status.monthlyLimit} aria-valuenow={status.usedThisMonth}>
        <div className={cn("h-2 rounded-full", pct >= 90 ? "bg-destructive" : "bg-primary")} style={{width: pct + "%"}}/></div>}
      {problems.map(p => <p key={p} className="flex items-start gap-2 text-sm text-muted-foreground"><CircleAlert className="mt-0.5 size-4 shrink-0 text-warning"/>{p}</p>)}
    </CardContent>
  </Card>;
}

function SettingsForm({store, initial, onSaved}: {store: string; initial: Settings; onSaved: (s: Settings) => void}) {
  const [enabled, setEnabled] = useState(initial.enabled);
  const [instructions, setInstructions] = useState(initial.instructions);
  const [sla, setSla] = useState(String(initial.slaMinutes));
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const saved = await ownerApi.json<Settings>("stores/" + store + "/assistant/settings",
        {enabled, instructions, slaMinutes: Number(sla), revision: initial.revision}, "PUT");
      toast.success(saved.enabled ? "Asistente activado" : "Configuración guardada");
      onSaved(saved);
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  return <Card><form onSubmit={save}>
    <CardHeader><CardTitle>Configuración</CardTitle>
      <CardDescription>La IA nunca inventa precios ni stock, no promete descuentos ni cambios, y pasa a tu equipo los reclamos.</CardDescription></CardHeader>
    <CardContent className="grid gap-5">
      <label className="flex items-start gap-3 rounded-lg border p-4">
        <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} className="mt-1 size-4 accent-[var(--primary)]"/>
        <span className="grid gap-0.5"><span className="font-medium">Responder automáticamente en WhatsApp</span>
          <span className="text-sm text-muted-foreground">Puedes pausarla en una conversación desde la bandeja cuando quieras.</span></span>
      </label>
      <Field label="Indicaciones para tu tienda" htmlFor="ai-instructions" hint={instructions.length + "/2000. Tono, políticas de envío, horarios de atención, lo que no debe ofrecer."}>
        <textarea id="ai-instructions" value={instructions} onChange={e => setInstructions(e.target.value)} maxLength={2000} rows={6}
          placeholder={"Ej.: Tutea al cliente y usa emojis con moderación.\nEnviamos a todo Lima en 24 a 48 horas.\nLos cambios se coordinan con el equipo."}
          className="rounded-lg border border-input bg-background px-3 py-2 text-sm"/>
      </Field>
      <Field label="Tiempo de respuesta del equipo (minutos)" htmlFor="ai-sla" hint="Si una conversación escalada espera más que esto, la bandeja la marca como sin respuesta.">
        <Input id="ai-sla" type="number" min={1} max={1440} value={sla} onChange={e => setSla(e.target.value)} required className="max-w-32"/>
      </Field>
      <div><Button type="submit" loading={busy}>Guardar</Button></div>
    </CardContent>
  </form></Card>;
}

function Tester({store, configured}: {store: string; configured: boolean}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function ask(event: FormEvent) {
    event.preventDefault();
    const message = text.trim();
    if (!message) return;
    setBusy(true); setError("");
    const history = turns.slice(-12).map(({author, text}) => ({author, text}));
    setTurns(t => [...t, {author: "CUSTOMER", text: message}]);
    setText("");
    try {
      const a = await ownerApi.json<Answer>("stores/" + store + "/assistant/test", {message, history});
      const note = [a.toolsUsed.map(t => toolLabels[t] ?? t).join(", "), a.escalate && ("Escalaría: " + (a.reason ?? ""))].filter(Boolean).join(" · ");
      setTurns(t => [...t, {author: "AI", text: a.text, note}]);
    } catch (e) { setError(e instanceof ApiError ? e.message : "No se pudo probar."); }
    finally { setBusy(false); }
  }

  return <Card className="flex h-fit flex-col xl:sticky xl:top-6">
    <CardHeader><CardTitle className="flex items-center gap-2"><Sparkles className="size-5 text-primary"/>Pruébala</CardTitle>
      <CardDescription>Escribe como si fueras un cliente. No se envía nada por WhatsApp ni cuenta para tu plan.</CardDescription></CardHeader>
    <CardContent className="grid gap-3">
      <ol className="grid max-h-[26rem] min-h-40 content-start gap-2 overflow-y-auto rounded-lg bg-muted/40 p-3" aria-label="Conversación de prueba">
        {!turns.length && <li className="text-sm text-muted-foreground">Prueba: “¿Tienen polos negros en talla M?”</li>}
        {turns.map((t, i) => <li key={i} className={cn("flex flex-col", t.author === "AI" ? "items-end" : "items-start")}>
          <p className={cn("max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm", t.author === "AI" ? "bg-accent text-accent-foreground" : "bg-card shadow-sm")}>{t.text}</p>
          {t.note && <p className="mt-0.5 flex items-center gap-1 text-[11px] text-muted-foreground"><Wrench className="size-3"/>{t.note}</p>}
        </li>)}
      </ol>
      {error && <Alert variant="destructive">{error}</Alert>}
      <form onSubmit={ask} className="flex gap-2">
        <Input value={text} onChange={e => setText(e.target.value)} maxLength={1000} placeholder="Mensaje del cliente" aria-label="Mensaje del cliente" disabled={!configured}/>
        <Button type="submit" loading={busy} disabled={!configured || !text.trim()} aria-label="Enviar prueba">{!busy && <Send/>}</Button>
      </form>
      {!configured && <p className="text-xs text-muted-foreground">La prueba se habilita cuando el servidor tenga la clave de Gemini.</p>}
      {turns.length > 0 && <button type="button" onClick={() => setTurns([])} className="justify-self-start text-xs text-muted-foreground underline">Empezar de nuevo</button>}
    </CardContent>
  </Card>;
}
