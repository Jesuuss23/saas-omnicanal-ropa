"use client";

import {useEffect, useRef, useState, type FormEvent} from "react";
import {AlarmClock, ArrowLeft, Bot, CheckCheck, ExternalLink, FileText, Hand, Lock, Maximize2, MessagesSquare, Minimize2, Mic, Paperclip, RotateCcw, ScanSearch, Send, ShieldAlert, Sparkles, UserRound, X} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, errorMessage, ownerApi} from "@/lib/api";
import {cn} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Status = "OPEN" | "ESCALATED" | "CLOSED";
type Row = {chat: string; phone: string | null; contactName: string | null; status: Status; aiEnabled: boolean; assignee: string | null;
  lastMessage: string | null; lastAuthor: string | null; lastInboundAt: string | null; lastOutboundAt: string | null;
  escalatedAt: string | null; overdue: boolean; aiPausedReason: "OFF_TOPIC" | "NSFW" | null; offTopicCount: number};
type MediaType = "IMAGE" | "VIDEO" | "AUDIO" | "DOCUMENT";
type Message = {author: "CUSTOMER" | "AI" | "HUMAN" | "PHONE"; body: string; at: string; status: string | null;
  mediaType?: MediaType | null; mimeType?: string | null; mediaUrl?: string | null; mediaStatus?: "PENDING" | "STORED" | "FAILED" | "TOO_LARGE" | "BLOCKED_NSFW" | null;
  caption?: string | null; transcript?: string | null; moderation?: "SAFE" | "BLOCKED_NSFW" | "FAILED" | "DISABLED" | null;
  vision?: Vision | null};
type Vision = {esConsultaValidaTienda: boolean; tipoPrenda: string; subcategoria: string; color: string; estilo: string; resumen: string;
  coincidencias: {productId: string; nombre: string; categoria: string; subcategoria: string | null; colores: string; tallas: string; precioDesde: number; stock: number}[]};
const placeholders = new Set(["[Imagen]", "[Video]", "[Nota de voz]", "[Documento]"]);
const ACCEPT = "image/jpeg,image/png,image/webp,video/mp4,application/pdf";
const MAX_FILE = 16 * 1024 * 1024;
type Conversation = {row: Row; escalationReason: string | null; summary: string | null; handbackNote: string | null; revision: number; messages: Message[]};
type Counts = {open: number; escalated: number; overdue: number};

const tabs: {status: Status | "ALL"; label: string}[] = [{status: "ALL", label: "Todas"}, {status: "OPEN", label: "Abiertas"}, {status: "ESCALATED", label: "Para el equipo"}, {status: "CLOSED", label: "Cerradas"}];
const time = new Intl.DateTimeFormat("es-PE", {timeZone: "America/Lima", hour: "2-digit", minute: "2-digit"});
const day = new Intl.DateTimeFormat("es-PE", {timeZone: "America/Lima", day: "numeric", month: "short"});
const when = (iso: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toDateString() === new Date().toDateString() ? time.format(d) : day.format(d);
};
const name = (r: Row) => r.contactName ?? (r.phone ? "+" + r.phone : r.chat);

export default function Inbox({store, initialChat, popup = false}: {store: string; initialChat: string | null; popup?: boolean}) {
  const base = "stores/" + store + "/inbox/conversations";
  // "Todas" por defecto: al tomar una conversación o cuando la IA la escala no desaparece de la lista.
  const [status, setStatus] = useState<Status | "ALL">("ALL");
  const [selected, setSelected] = useState<string | null>(initialChat);
  const [version, setVersion] = useState(0);
  const list = useApiData<Row[]>(ownerApi.url(status === "ALL" ? base : base + "?status=" + status), version);
  const counts = useApiData<Counts>(ownerApi.url(base + "/counts"), version);

  // La bandeja se actualiza sola cada 20 segundos mientras está abierta.
  useEffect(() => {
    const timer = setInterval(() => setVersion(v => v + 1), 20000);
    return () => clearInterval(timer);
  }, []);

  const [expanded, setExpanded] = useState(popup);
  // Pantalla completa: la bandeja tapa el panel y ocupa toda la ventana (también al abrirla en otra ventana).
  useEffect(() => {
    if (!expanded) return;
    const close = (e: KeyboardEvent) => { if (e.key === "Escape" && !popup) setExpanded(false); };
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", close);
    return () => { document.body.style.overflow = ""; window.removeEventListener("keydown", close); };
  }, [expanded, popup]);

  function openWindow() {
    const url = "/propietario/" + encodeURIComponent(store) + "/whatsapp?ventana=1" + (selected ? "&chat=" + encodeURIComponent(selected) : "");
    window.open(url, "bandeja-whatsapp-" + store, "popup,width=1280,height=860");
  }

  return <div className={cn("flex flex-col gap-3", expanded ? "fixed inset-0 z-50 bg-background p-3 sm:p-4" : "h-[calc(100dvh-13rem)] min-h-[26rem] lg:h-[calc(100dvh-15rem)]")}>
    <div className="flex items-center justify-end gap-2">
      {expanded && <p className="mr-auto flex items-center gap-2 font-semibold"><MessagesSquare className="size-4 text-primary"/>Bandeja de WhatsApp</p>}
      {!popup && <Button size="sm" variant="outline" onClick={openWindow}><ExternalLink/>Abrir en otra ventana</Button>}
      {!popup && <Button size="sm" variant="outline" onClick={() => setExpanded(v => !v)} aria-pressed={expanded}>
        {expanded ? <><Minimize2/>Salir de pantalla completa</> : <><Maximize2/>Pantalla completa</>}</Button>}
    </div>
    <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-[22rem_minmax(0,1fr)]">
    <Card className={cn("flex h-full min-h-0 min-w-0 flex-col overflow-hidden p-0", selected && "hidden lg:flex")}>
      <div className="flex gap-1 border-b p-2" role="tablist" aria-label="Estado de las conversaciones">
        {tabs.map(t => <button key={t.status} role="tab" aria-selected={status === t.status} onClick={() => setStatus(t.status)}
          className={cn("flex flex-1 items-center justify-center gap-1.5 rounded-md px-1.5 py-1.5 text-xs font-medium sm:text-sm",
            status === t.status ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:bg-muted")}>
          {t.label}
          {t.status === "ESCALATED" && counts.data && counts.data.escalated > 0 &&
            <span className={cn("rounded-full px-1.5 text-xs", counts.data.overdue ? "bg-destructive text-destructive-foreground" : "bg-warning/30")}>{counts.data.escalated}</span>}
        </button>)}
      </div>
      {list.error && <Alert variant="destructive" className="m-3">{list.error.message}</Alert>}
      {!list.data ? <div className="grid gap-2 p-3">{Array.from({length: 5}, (_, i) => <Skeleton key={i} className="h-14"/>)}</div>
        : !list.data.length ? <EmptyState icon={MessagesSquare} title="Sin conversaciones aquí"
          description={status === "ESCALATED" ? "Cuando la IA necesite ayuda o un cliente pida una persona, aparecerá aquí." : "Los mensajes de WhatsApp aparecen aquí apenas llegan."}/>
        : <ul className="flex-1 divide-y overflow-y-auto">{list.data.map(r => <li key={r.chat}>
          <button type="button" onClick={() => setSelected(r.chat)} aria-current={selected === r.chat}
            className={cn("grid w-full min-w-0 grid-cols-[minmax(0,1fr)] gap-0.5 px-4 py-3 text-left hover:bg-muted/60", selected === r.chat && "bg-accent/60")}>
            <span className="flex items-center gap-2">
              <span className="truncate font-medium">{name(r)}</span>
              {r.overdue && <Badge variant="destructive"><AlarmClock/>Sin respuesta</Badge>}
              {status === "ALL" && r.status !== "OPEN" && <Badge variant={r.status === "ESCALATED" ? "warning" : "neutral"}>
                {r.status === "ESCALATED" ? "Para el equipo" : "Cerrada"}</Badge>}
              <span className="ml-auto shrink-0 text-xs text-muted-foreground">{when(r.lastInboundAt ?? r.lastOutboundAt)}</span>
            </span>
            <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
              {r.lastAuthor === "AI" && <Bot className="size-3.5 shrink-0"/>}{(r.lastAuthor === "HUMAN" || r.lastAuthor === "PHONE") && <UserRound className="size-3.5 shrink-0"/>}
              <span className="truncate">{r.lastMessage ?? ""}</span>
              {!r.aiEnabled && <span className="ml-auto shrink-0 text-xs">{r.aiPausedReason === "OFF_TOPIC" ? "IA pausada: fuera de tema" : r.aiPausedReason === "NSFW" ? "IA pausada: imagen explícita" : "IA pausada"}</span>}
            </span>
          </button></li>)}</ul>}
    </Card>
    {selected ? <Thread key={selected} store={store} chat={selected} onBack={() => setSelected(null)} onChanged={() => setVersion(v => v + 1)} version={version}/>
      : <Card className="hidden h-full place-items-center lg:grid"><EmptyState icon={MessagesSquare} title="Elige una conversación"
        description="La IA responde sola las consultas de catálogo, precios y stock. Tú tomas las que necesitan a una persona."/></Card>}
    </div>
  </div>;
}

function Thread({store, chat, onBack, onChanged, version}: {store: string; chat: string; onBack: () => void; onChanged: () => void; version: number}) {
  const base = "stores/" + store + "/inbox/conversations";
  const q = "?chat=" + encodeURIComponent(chat);
  const {data, error, mutate} = useApiData<Conversation>(ownerApi.url(base + "/detail" + q), version);
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState("");
  const [handback, setHandback] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const picker = useRef<HTMLInputElement>(null);
  const caps = useApiData<{send: boolean}>(ownerApi.url("stores/" + store + "/whatsapp/media/capabilities"));
  const canSendMedia = caps.data?.send ?? false;
  const fileLocked = !!file && !file.type.includes("pdf") && !canSendMedia;
  const count = data?.messages.length ?? 0;
  const messages = useRef<HTMLOListElement>(null);
  // Baja al último mensaje dentro de la lista, sin mover la página.
  useEffect(() => { const el = messages.current; if (el) el.scrollTop = el.scrollHeight; }, [count]);

  async function act(path: string, body?: unknown, method: "POST" | "PUT" = "POST", done?: string) {
    setBusy(true);
    try {
      mutate(await ownerApi.json<Conversation>(base + "/" + path + q, body, method));
      if (done) toast.success(done);
      onChanged();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo completar la acción."); }
    finally { setBusy(false); }
  }

  function pick(selected: File | undefined) {
    if (picker.current) picker.current.value = "";
    if (!selected) return;
    if (!ACCEPT.split(",").includes(selected.type)) { toast.error("Solo puedes enviar imágenes JPG, PNG o WebP, videos MP4 o PDF."); return; }
    if (selected.size > MAX_FILE) { toast.error("El archivo supera 16 MB."); return; }
    setFile(selected);
  }

  async function reply(event: FormEvent) {
    event.preventDefault();
    const message = text.trim();
    if (!message && !file) return;
    if (fileLocked) return;
    setBusy(true);
    try {
      // Tomar la conversación al responder: la IA no contesta encima de una persona.
      if (data?.row.aiEnabled && data.row.status !== "ESCALATED") mutate(await ownerApi.json<Conversation>(base + "/take" + q));
      let status: string;
      if (file) {
        const form = new FormData();
        form.set("chat", chat);
        form.set("caption", message.slice(0, 1000));
        form.set("file", file);
        const res = await ownerApi.send("stores/" + store + "/whatsapp/replies/" + crypto.randomUUID() + "/media", form);
        if (!res.ok) throw new ApiError(res.status, await errorMessage(res, "No se pudo enviar el archivo."));
        status = (await res.json()).status;
      } else {
        status = (await ownerApi.json<{status: string}>("stores/" + store + "/whatsapp/replies/" + crypto.randomUUID(), {chat, text: message}, "PUT")).status;
      }
      if (status === "REJECTED") toast.error("WhatsApp rechazó el mensaje. Revisa la conexión del número.");
      setText("");
      setFile(null);
      mutate(await ownerApi.get<Conversation>(base + "/detail" + q));
      onChanged();
    } catch (e) {
      // El backend ya consultó Evolution en vivo antes de responder 409, así que su mensaje es el motivo real.
      toast.error(!(e instanceof ApiError) ? "No se pudo enviar."
        : e.status === 409 && (!e.message || e.message === "Conflict") ? "Conecta el número de WhatsApp antes de responder." : e.message);
    }
    finally { setBusy(false); }
  }

  if (error) return <Alert variant="destructive">{error.message}</Alert>;
  if (!data) return <Card className="grid h-full content-start gap-3 p-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-12"/>)}</Card>;
  const r = data.row;
  return <Card className="flex h-full min-h-0 flex-col overflow-hidden p-0">
    <header className="flex flex-wrap items-center gap-2 border-b px-4 py-3">
      <Button variant="ghost" size="sm" className="lg:hidden" onClick={onBack} aria-label="Volver a la lista"><ArrowLeft/></Button>
      <div className="min-w-0 flex-1"><h2 className="truncate font-semibold">{name(r)}</h2>
        <p className="text-xs text-muted-foreground">{r.phone && "+" + r.phone}{r.assignee && " · Atiende " + r.assignee}</p></div>
      <Badge variant={r.status === "ESCALATED" ? "warning" : r.status === "OPEN" ? "default" : "neutral"}>
        {r.status === "ESCALATED" ? "Para el equipo" : r.status === "OPEN" ? "Abierta" : "Cerrada"}</Badge>
      <Button size="sm" variant="outline" disabled={busy} onClick={() => act("ai", {enabled: !r.aiEnabled}, "PUT", r.aiEnabled ? "IA pausada en esta conversación" : "IA activada en esta conversación")}>
        <Bot/>{r.aiEnabled ? "Pausar IA" : "Activar IA"}</Button>
      {r.status === "ESCALATED" ? <Button size="sm" disabled={busy} onClick={() => setHandback(true)}><Sparkles/>Devolver a la IA</Button>
        : <Button size="sm" variant="outline" disabled={busy} onClick={() => act("take", undefined, "POST", "Tomaste la conversación")}><Hand/>Tomar</Button>}
      {r.status === "CLOSED" ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => act("status", {status: "OPEN"}, "PUT")}><RotateCcw/>Reabrir</Button>
        : <Button size="sm" variant="ghost" disabled={busy} onClick={() => act("status", {status: "CLOSED"}, "PUT", "Conversación cerrada")}><CheckCheck/>Cerrar</Button>}
    </header>
    {r.aiPausedReason === "NSFW" && !r.aiEnabled && <div className="border-b bg-destructive/10 px-4 py-3 text-sm">
      <p className="flex items-center gap-1.5 font-medium text-destructive"><ShieldAlert className="size-4"/>El cliente envió una imagen explícita</p>
      <p className="mt-0.5 text-muted-foreground">Se le envió un aviso automático y la IA quedó pausada en esta conversación. Revisa el caso y
        actívala de nuevo si corresponde.</p></div>}
    {r.aiPausedReason === "OFF_TOPIC" && !r.aiEnabled && <div className="border-b bg-muted/60 px-4 py-3 text-sm text-muted-foreground">
      La IA se pausó sola en esta conversación porque el cliente hizo dos preguntas seguidas ajenas a la tienda. No responde ni gasta respuestas del plan
      hasta que la actives, tomes la conversación o la cierres y el cliente vuelva a escribir.</div>}
    {r.status === "ESCALATED" && (data.escalationReason || data.summary) && <div className="border-b bg-warning/10 px-4 py-3 text-sm">
      <p className="font-medium">{data.escalationReason}</p>{data.summary && <p className="mt-0.5 text-muted-foreground">{data.summary}</p>}
      {r.overdue && <p className="mt-1 flex items-center gap-1 font-medium text-destructive"><AlarmClock className="size-4"/>Pasó el tiempo de respuesta acordado.</p>}
    </div>}
    <ol ref={messages} className="min-h-0 flex-1 space-y-2 overflow-y-auto bg-muted/30 px-4 py-4" aria-label="Mensajes">
      {data.messages.map((m, i) => <li key={i} className={cn("flex", m.author !== "CUSTOMER" && "justify-end")}>
        <div className={cn("max-w-[80%] rounded-2xl px-3 py-2 text-sm shadow-sm", m.author === "CUSTOMER" ? "rounded-bl-sm bg-card" :
          m.author === "AI" ? "rounded-br-sm bg-accent text-accent-foreground" : "rounded-br-sm bg-primary text-primary-foreground")}>
          {m.mediaType && <MediaView store={store} m={m}/>}
          {!(m.mediaType && placeholders.has(m.body)) && <p className="whitespace-pre-wrap break-words">{m.body}</p>}
          <p className="mt-1 flex items-center justify-end gap-1 text-[11px] opacity-70">
            {m.author === "AI" && <><Bot className="size-3"/>IA · </>}{m.author === "HUMAN" && "Equipo · "}{m.author === "PHONE" && "Desde el celular · "}{when(m.at)}
            {m.status === "READ" && " · Leído"}{m.status === "REJECTED" && " · No enviado"}</p>
        </div>
      </li>)}
    </ol>
    <form onSubmit={reply} className="grid gap-2 border-t p-3">
      {file && <div className={cn("flex items-center gap-2 rounded-lg border px-3 py-2 text-sm", fileLocked ? "border-warning/40 bg-warning/10" : "bg-muted/40")}>
        {fileLocked ? <Lock className="size-4 shrink-0 text-warning"/> : file.type.includes("pdf") ? <FileText className="size-4 shrink-0"/> : <Paperclip className="size-4 shrink-0"/>}
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium">{file.name}</p>
          <p className="text-xs text-muted-foreground">{fileLocked
            ? "Tu plan no incluye enviar imágenes ni videos por WhatsApp. Está disponible en el plan Premium."
            : (file.size / 1024 / 1024).toFixed(1) + " MB · el texto que escribas va como leyenda"}</p>
        </div>
        <Button type="button" variant="ghost" size="sm" onClick={() => setFile(null)} aria-label="Quitar archivo"><X/></Button>
      </div>}
      <div className="flex gap-2">
        <input ref={picker} type="file" accept={ACCEPT} className="hidden" onChange={e => pick(e.target.files?.[0])} aria-label="Adjuntar archivo"/>
        <Button type="button" variant="outline" disabled={busy} onClick={() => picker.current?.click()} aria-label="Adjuntar imagen, video o PDF"
          title={canSendMedia ? "Adjuntar imagen, video o PDF" : "Adjuntar PDF (imágenes y videos en el plan Premium)"}><Paperclip/></Button>
        <label className="sr-only" htmlFor="reply">Responder</label>
        <textarea id="reply" value={text} onChange={e => setText(e.target.value)} maxLength={file ? 1000 : 4000} rows={1}
          placeholder={file ? "Leyenda (opcional)…" : "Escribe una respuesta…"}
          onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit(); } }}
          className="min-h-10 flex-1 resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm"/>
        <Button type="submit" disabled={busy || fileLocked || (!text.trim() && !file)} aria-label="Enviar"><Send/></Button>
      </div>
    </form>
    <HandbackDialog open={handback} onClose={() => setHandback(false)} busy={busy}
      onConfirm={note => act("handback", {note}, "POST", "La IA vuelve a atender esta conversación").then(() => setHandback(false))}/>
  </Card>;
}

/** Archivo de un mensaje: foto, video, nota de voz o documento, servido por el backend solo al equipo de la tienda. */
function MediaView({store, m}: {store: string; m: Message}) {
  const [reveal, setReveal] = useState(false);
  if (m.mediaStatus === "PENDING" || (!m.mediaUrl && !m.mediaStatus))
    return <p className="flex items-center gap-1.5 text-xs opacity-80"><Paperclip className="size-3.5"/>Descargando {label(m.mediaType)}…</p>;
  if (!m.mediaUrl)
    return <p className="flex items-center gap-1.5 text-xs opacity-80"><Paperclip className="size-3.5"/>
      {m.mediaStatus === "TOO_LARGE" ? "El archivo supera 16 MB y no se guardó." : "No se pudo descargar " + label(m.mediaType) + "."}</p>;
  const src = ownerApi.url("stores/" + store + "/whatsapp" + m.mediaUrl);
  const blocked = m.mediaStatus === "BLOCKED_NSFW" || m.moderation === "BLOCKED_NSFW";
  switch (m.mediaType) {
    case "IMAGE":
      if (blocked && !reveal)
        return <div className="mb-1 grid w-64 max-w-full place-items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-5 text-center text-xs">
          <ShieldAlert className="size-6 text-destructive"/>
          <p className="font-medium text-destructive">Imagen bloqueada por contenido explícito</p>
          <p className="text-muted-foreground">No se analizó ni se envió a la IA. El cliente recibió un aviso.</p>
          <button type="button" onClick={() => setReveal(true)} className="text-muted-foreground underline">Ver de todos modos</button>
        </div>;
      return <div className="mb-1 grid gap-1.5">
        <a href={blocked ? src + "?reveal=true" : src} target="_blank" rel="noreferrer" className="block">
          {/* eslint-disable-next-line @next/next/no-img-element -- archivo privado del panel, no pasa por next/image */}
          <img src={blocked ? src + "?reveal=true" : src} alt={m.caption ?? "Imagen del chat"} loading="lazy" className="max-h-72 w-full rounded-lg object-cover"/></a>
        {m.vision && <VisionSummary v={m.vision}/>}
      </div>;
    case "VIDEO":
      return <video src={src} controls preload="metadata" className="mb-1 max-h-72 w-full rounded-lg bg-black"/>;
    case "AUDIO":
      return <div className="mb-1 grid gap-1">
        <div className="flex items-center gap-2"><Mic className="size-4 shrink-0 opacity-70"/>
          <audio src={src} controls preload="metadata" className="h-9 w-56 max-w-full"/></div>
        {m.transcript && <p className="rounded-md bg-background/30 px-2 py-1 text-xs italic">“{m.transcript}”</p>}
      </div>;
    default:
      return <a href={src} target="_blank" rel="noreferrer" className="mb-1 flex items-center gap-2 rounded-lg bg-background/20 px-2 py-1.5 underline-offset-2 hover:underline">
        <FileText className="size-4 shrink-0"/>{m.mimeType?.includes("pdf") ? "Ver PDF" : "Ver documento"}</a>;
  }
}

/** Lo que vio el análisis visual en la foto y las prendas parecidas que se le pasaron a la IA. */
function VisionSummary({v}: {v: Vision}) {
  if (!v.esConsultaValidaTienda)
    return <p className="flex items-start gap-1.5 rounded-md bg-warning/15 px-2 py-1 text-xs"><ScanSearch className="mt-0.5 size-3.5 shrink-0"/>
      Foto ajena a la tienda: {v.resumen || "sin prendas"}. Se aplicó la regla de fuera de tema.</p>;
  const tags = [v.tipoPrenda, v.subcategoria, v.color, v.estilo].filter(Boolean);
  return <div className="grid gap-1 rounded-md bg-background/30 px-2 py-1.5 text-xs">
    <p className="flex items-center gap-1.5 font-medium"><ScanSearch className="size-3.5"/>Detectado</p>
    <div className="flex flex-wrap gap-1">{tags.map(t => <span key={t} className="rounded-full bg-background/60 px-2 py-0.5">{t}</span>)}</div>
    {v.coincidencias.length > 0
      ? <p className="opacity-80">Parecidas: {v.coincidencias.map(c => c.nombre).join(", ")}</p>
      : <p className="opacity-80">Sin prendas parecidas con stock.</p>}
  </div>;
}

const label = (t: MediaType | null | undefined) => t === "IMAGE" ? "la imagen" : t === "VIDEO" ? "el video" : t === "AUDIO" ? "la nota de voz" : "el archivo";

function HandbackDialog({open, onClose, onConfirm, busy}: {open: boolean; onClose: () => void; onConfirm: (note: string) => void; busy: boolean}) {
  const [note, setNote] = useState("");
  return <Dialog open={open} onOpenChange={next => { if (!next) onClose(); }}>
    <DialogContent>
      <DialogHeader><DialogTitle>Devolver a la IA</DialogTitle>
        <DialogDescription>Cuéntale en una o dos frases qué resolviste, así la IA continúa sin repetir preguntas.</DialogDescription></DialogHeader>
      <label className="grid gap-1 text-sm"><span className="font-medium">Nota para la IA (opcional)</span>
        <textarea value={note} onChange={e => setNote(e.target.value)} maxLength={1000} rows={3}
          placeholder="Ej.: Ya le cambiamos la talla a L; solo falta confirmar el delivery."
          className="rounded-lg border border-input bg-background px-3 py-2"/></label>
      <DialogFooter><Button variant="ghost" onClick={onClose}><X/>Cancelar</Button>
        <Button loading={busy} onClick={() => onConfirm(note)}>Devolver a la IA</Button></DialogFooter>
    </DialogContent>
  </Dialog>;
}
