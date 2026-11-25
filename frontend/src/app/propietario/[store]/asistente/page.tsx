"use client";

import {useEffect, useRef, useState, type FormEvent} from "react";
import {Bot, CircleAlert, FileText, Clock, PauseCircle, Send, Sparkles, Wrench} from "lucide-react";
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
import CategoryCatalogs from "../CategoryCatalogs";
import {INSTRUCTION_TEMPLATE} from "./instructionTemplate";

type Settings = {enabled: boolean; instructions: string; slaMinutes: number; revision: number; nsfwPauseAi: boolean};
type Status = {configured: boolean; platformEnabled: boolean; planIncluded: boolean; usedThisMonth: number; monthlyLimit: number | null; debounceMs: number};
type Answer = {text: string; escalate: boolean; reason: string | null; summary: string | null; toolsUsed: string[]; offTopic: boolean; paused: boolean;
  attachments?: {kind: string; description: string}[]};
type Turn = {author: "CUSTOMER" | "AI" | "SYSTEM"; text: string; note?: string};

const toolLabels: Record<string, string> = {buscarPrendas: "consultó el catálogo", infoTienda: "consultó recojo y delivery", escalar: "pidió pasar a una persona", fueraDeTema: "detectó una pregunta ajena a la tienda", categorias: "revisó las categorías", enviarCatalogo: "eligió un catálogo para enviar", enviarFotoPrenda: "eligió la foto de una prenda"};

export default function AssistantPage() {
  const {store, send} = useStore();
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
        <CategoryCatalogs store={store} send={send} compact/>
      </div>
      <Tester store={store} configured={status.data?.configured ?? true} debounceMs={status.data?.debounceMs ?? 8000}/>
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
  // Una tienda nueva arranca con la plantilla recomendada ya escrita: solo completa sus datos y guarda.
  const [instructions, setInstructions] = useState(initial.instructions || INSTRUCTION_TEMPLATE);
  const [sla, setSla] = useState(String(initial.slaMinutes));
  const [nsfwPause, setNsfwPause] = useState(initial.nsfwPauseAi ?? true);
  const usingTemplate = !initial.instructions && instructions === INSTRUCTION_TEMPLATE;
  const pending = (instructions.match(/\[[^\]]+\]/g) ?? []).length;

  function insertTemplate() {
    if (instructions.trim() && instructions !== INSTRUCTION_TEMPLATE
      && !window.confirm("Se reemplazarán tus indicaciones actuales por la plantilla recomendada. ¿Continuar?")) return;
    setInstructions(INSTRUCTION_TEMPLATE);
  }
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const saved = await ownerApi.json<Settings>("stores/" + store + "/assistant/settings",
        {enabled, instructions, slaMinutes: Number(sla), revision: initial.revision, nsfwPauseAi: nsfwPause}, "PUT");
      toast.success(saved.enabled ? "Asistente activado" : "Configuración guardada");
      onSaved(saved);
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo guardar."); }
    finally { setBusy(false); }
  }

  return <Card><form onSubmit={save}>
    <CardHeader><CardTitle>Configuración</CardTitle>
      <CardDescription>La IA nunca inventa precios ni stock, no promete descuentos ni cambios, y pasa a tu equipo los reclamos.
        Solo atiende temas de tu tienda: a la segunda pregunta ajena seguida (tareas, clima, chistes…) se pausa en esa conversación sin ocupar a tu equipo.</CardDescription></CardHeader>
    <CardContent className="grid gap-5">
      <label className="flex items-start gap-3 rounded-lg border p-4">
        <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} className="mt-1 size-4 accent-[var(--primary)]"/>
        <span className="grid gap-0.5"><span className="font-medium">Responder automáticamente en WhatsApp</span>
          <span className="text-sm text-muted-foreground">Puedes pausarla en una conversación desde la bandeja cuando quieras.</span></span>
      </label>
      <div className="grid gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <label htmlFor="ai-instructions" className="text-sm font-medium">Indicaciones para tu tienda</label>
          <Button type="button" size="sm" variant="outline" onClick={insertTemplate}><FileText/>Insertar plantilla recomendada</Button>
        </div>
        {usingTemplate && <p className="rounded-lg bg-primary/5 px-3 py-2 text-xs text-muted-foreground">
          Te dejamos una plantilla lista con tono, guía por categorías, tallas y stock, envíos, pagos y cuándo pasar a una persona.
          Completa lo que está entre [corchetes] y guarda.</p>}
      </div>
      <div className="-mt-3 grid gap-1.5">
        <textarea id="ai-instructions" value={instructions} onChange={e => setInstructions(e.target.value)} maxLength={4000} rows={14}
          placeholder={"Ej.: Tutea al cliente y usa emojis con moderación.\nEnviamos a todo Lima en 24 a 48 horas.\nLos cambios se coordinan con el equipo."}
          className="rounded-lg border border-input bg-background px-3 py-2 text-sm"/>
        <p className="text-xs text-muted-foreground">{instructions.length}/4000{pending ? ` · ${pending} datos entre [corchetes] por completar` : ""}. Tono, envíos, pagos, cambios y lo que no debe ofrecer.</p>
      </div>
      <Field label="Tiempo de respuesta del equipo (minutos)" htmlFor="ai-sla" hint="Si una conversación escalada espera más que esto, la bandeja la marca como sin respuesta.">
        <Input id="ai-sla" type="number" min={1} max={1440} value={sla} onChange={e => setSla(e.target.value)} required className="max-w-32"/>
      </Field>
      <label className="flex items-start gap-3 rounded-lg border p-4">
        <input type="checkbox" checked={nsfwPause} onChange={e => setNsfwPause(e.target.checked)} className="mt-1 size-4 accent-[var(--primary)]"/>
        <span className="grid gap-0.5"><span className="font-medium">Pausar la IA si un cliente envía una imagen explícita</span>
          <span className="text-sm text-muted-foreground">La imagen siempre se bloquea en la bandeja y el cliente recibe un aviso automático. Con esta opción, además,
            la IA deja de responder en esa conversación hasta que tu equipo la active.</span></span>
      </label>
      <div><Button type="submit" loading={busy}>Guardar</Button></div>
    </CardContent>
  </form></Card>;
}

function Tester({store, configured, debounceMs}: {store: string; configured: boolean; debounceMs: number}) {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [error, setError] = useState("");
  const [offTopic, setOffTopic] = useState(0);
  const [paused, setPaused] = useState(false);
  // Igual que en WhatsApp: los mensajes seguidos se juntan y reciben una sola respuesta al terminar la espera.
  const queue = useRef<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const inFlight = useRef(false);
  const state = useRef({turns, offTopic, paused});
  useEffect(() => { state.current = {turns, offTopic, paused}; });
  const generation = useRef(0);

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  function schedule() {
    if (timer.current) clearTimeout(timer.current);
    setWaiting(true);
    timer.current = setTimeout(flush, debounceMs);
  }

  async function flush() {
    timer.current = null;
    if (inFlight.current) { schedule(); return; }
    const batch = queue.current.splice(0);
    setWaiting(false);
    if (!batch.length) return;
    const {turns: current, offTopic: count, paused: isPaused} = state.current;
    if (isPaused) {
      setTurns(t => [...t, {author: "SYSTEM", text: "La IA ignora este mensaje sin llamar al modelo: está pausada por preguntas ajenas a la tienda."}]);
      return;
    }
    const gen = generation.current;
    inFlight.current = true; setBusy(true); setError("");
    // Historial sin los mensajes del lote, que van juntos como mensaje actual.
    const rest = current.filter(t => t.author !== "SYSTEM");
    const left = [...batch];
    for (let i = rest.length - 1; i >= 0 && left.length; i--) {
      const at = rest[i].author === "CUSTOMER" ? left.lastIndexOf(rest[i].text) : -1;
      if (at >= 0) { left.splice(at, 1); rest.splice(i, 1); }
    }
    const history = rest.slice(-12).map(({author, text}) => ({author: author as "CUSTOMER" | "AI", text}));
    try {
      const a = await ownerApi.json<Answer>("stores/" + store + "/assistant/test", {message: batch.join("\n"), history, offTopicCount: count});
      if (gen !== generation.current) return;
      const note = [a.toolsUsed.map(t => toolLabels[t] ?? t).join(", "), a.escalate && ("Escalaría: " + (a.reason ?? "")),
        ...(a.attachments ?? []).map(f => "en WhatsApp enviaría " + f.description),
        batch.length > 1 && ("respondió " + batch.length + " mensajes juntos")].filter(Boolean).join(" · ");
      if (a.text) setTurns(t => [...t, {author: "AI", text: a.text, note}]);
      setOffTopic(a.offTopic ? count + 1 : 0);
      if (a.paused) {
        setPaused(true);
        setTurns(t => [...t, {author: "SYSTEM", text: "Asistente pausado en esta conversación. En WhatsApp se reactiva cuando tu equipo la activa, la toma o la conversación se cierra."}]);
      }
    } catch (e) { if (gen === generation.current) setError(e instanceof ApiError ? e.message : "No se pudo probar."); }
    finally {
      inFlight.current = false;
      if (gen === generation.current) setBusy(false);
    }
  }

  function ask(event: FormEvent) {
    event.preventDefault();
    const message = text.trim();
    if (!message) return;
    setTurns(t => [...t, {author: "CUSTOMER", text: message}]);
    setText("");
    queue.current.push(message);
    schedule();
  }

  function reset() {
    generation.current++;
    if (timer.current) clearTimeout(timer.current);
    queue.current = []; inFlight.current = false;
    setTurns([]); setOffTopic(0); setPaused(false); setWaiting(false); setBusy(false); setError("");
  }

  return <Card className="flex h-fit flex-col xl:sticky xl:top-6">
    <CardHeader><CardTitle className="flex items-center gap-2"><Sparkles className="size-5 text-primary"/>Pruébala</CardTitle>
      <CardDescription>Escribe como si fueras un cliente. No se envía nada por WhatsApp ni cuenta para tu plan.
        Si mandas varios mensajes seguidos, la IA espera {Math.round(debounceMs / 1000)} s y responde todos juntos, como en WhatsApp.</CardDescription></CardHeader>
    <CardContent className="grid gap-3">
      <ol className="grid max-h-[26rem] min-h-40 content-start gap-2 overflow-y-auto rounded-lg bg-muted/40 p-3" aria-label="Conversación de prueba">
        {!turns.length && <li className="text-sm text-muted-foreground">Prueba: “¿Tienen polos negros en talla M?”</li>}
        {turns.map((t, i) => t.author === "SYSTEM"
          ? <li key={i} className="flex items-start gap-1.5 justify-self-center rounded-md bg-warning/10 px-2 py-1 text-xs text-muted-foreground"><PauseCircle className="mt-0.5 size-3.5 shrink-0 text-warning"/>{t.text}</li>
          : <li key={i} className={cn("flex flex-col", t.author === "AI" ? "items-end" : "items-start")}>
          <p className={cn("max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm", t.author === "AI" ? "bg-accent text-accent-foreground" : "bg-card shadow-sm")}>{t.text}</p>
          {t.note && <p className="mt-0.5 flex items-center gap-1 text-[11px] text-muted-foreground"><Wrench className="size-3"/>{t.note}</p>}
        </li>)}
        {(waiting || busy) && <li className="flex items-center gap-1 justify-self-end text-[11px] text-muted-foreground" aria-live="polite">
          <Clock className="size-3"/>{busy ? "Respondiendo…" : "Esperando por si escribes algo más…"}</li>}
      </ol>
      {offTopic === 1 && !paused && <p className="text-xs text-muted-foreground">1 de 2 preguntas fuera de tema: a la siguiente la IA se pausa.</p>}
      {error && <Alert variant="destructive">{error}</Alert>}
      <form onSubmit={ask} className="flex gap-2">
        <Input value={text} onChange={e => setText(e.target.value)} maxLength={1000} placeholder="Mensaje del cliente" aria-label="Mensaje del cliente" disabled={!configured}/>
        <Button type="submit" disabled={!configured || !text.trim()} aria-label="Enviar prueba"><Send/></Button>
      </form>
      {!configured && <p className="text-xs text-muted-foreground">La prueba se habilita cuando el servidor tenga la clave de Gemini.</p>}
      {turns.length > 0 && <button type="button" onClick={reset} className="justify-self-start text-xs text-muted-foreground underline">Empezar de nuevo</button>}
    </CardContent>
  </Card>;
}
