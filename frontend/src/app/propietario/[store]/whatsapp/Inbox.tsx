"use client";

import {useEffect, useRef, useState, type FormEvent} from "react";
import {AlarmClock, ArrowLeft, Bot, CheckCheck, Hand, MessagesSquare, RotateCcw, Send, Sparkles, UserRound, X} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {EmptyState} from "@/components/ui/empty-state";
import {Skeleton} from "@/components/ui/skeleton";
import {toast} from "@/components/ui/toaster";
import {ApiError, ownerApi} from "@/lib/api";
import {cn} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

type Status = "OPEN" | "ESCALATED" | "CLOSED";
type Row = {chat: string; phone: string | null; contactName: string | null; status: Status; aiEnabled: boolean; assignee: string | null;
  lastMessage: string | null; lastAuthor: string | null; lastInboundAt: string | null; lastOutboundAt: string | null;
  escalatedAt: string | null; overdue: boolean};
type Message = {author: "CUSTOMER" | "AI" | "HUMAN"; body: string; at: string; status: string | null};
type Conversation = {row: Row; escalationReason: string | null; summary: string | null; handbackNote: string | null; revision: number; messages: Message[]};
type Counts = {open: number; escalated: number; overdue: number};

const tabs: {status: Status; label: string}[] = [{status: "OPEN", label: "Abiertas"}, {status: "ESCALATED", label: "Para el equipo"}, {status: "CLOSED", label: "Cerradas"}];
const time = new Intl.DateTimeFormat("es-PE", {timeZone: "America/Lima", hour: "2-digit", minute: "2-digit"});
const day = new Intl.DateTimeFormat("es-PE", {timeZone: "America/Lima", day: "numeric", month: "short"});
const when = (iso: string | null) => {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toDateString() === new Date().toDateString() ? time.format(d) : day.format(d);
};
const name = (r: Row) => r.contactName ?? (r.phone ? "+" + r.phone : r.chat);

export default function Inbox({store, initialChat}: {store: string; initialChat: string | null}) {
  const base = "stores/" + store + "/inbox/conversations";
  const [status, setStatus] = useState<Status>("OPEN");
  const [selected, setSelected] = useState<string | null>(initialChat);
  const [version, setVersion] = useState(0);
  const list = useApiData<Row[]>(ownerApi.url(base + "?status=" + status), version);
  const counts = useApiData<Counts>(ownerApi.url(base + "/counts"), version);

  // La bandeja se actualiza sola cada 20 segundos mientras está abierta.
  useEffect(() => {
    const timer = setInterval(() => setVersion(v => v + 1), 20000);
    return () => clearInterval(timer);
  }, []);

  return <div className="grid gap-4 lg:grid-cols-[22rem_minmax(0,1fr)]">
    <Card className={cn("flex min-h-[32rem] min-w-0 flex-col overflow-hidden p-0", selected && "hidden lg:flex")}>
      <div className="flex gap-1 border-b p-2" role="tablist" aria-label="Estado de las conversaciones">
        {tabs.map(t => <button key={t.status} role="tab" aria-selected={status === t.status} onClick={() => setStatus(t.status)}
          className={cn("flex flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-sm font-medium",
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
              <span className="ml-auto shrink-0 text-xs text-muted-foreground">{when(r.lastInboundAt)}</span>
            </span>
            <span className="flex items-center gap-1.5 text-sm text-muted-foreground">
              {r.lastAuthor === "AI" && <Bot className="size-3.5 shrink-0"/>}{r.lastAuthor === "HUMAN" && <UserRound className="size-3.5 shrink-0"/>}
              <span className="truncate">{r.lastMessage ?? ""}</span>
              {!r.aiEnabled && <span className="ml-auto shrink-0 text-xs">IA pausada</span>}
            </span>
          </button></li>)}</ul>}
    </Card>
    {selected ? <Thread key={selected} store={store} chat={selected} onBack={() => setSelected(null)} onChanged={() => setVersion(v => v + 1)} version={version}/>
      : <Card className="hidden min-h-[32rem] place-items-center lg:grid"><EmptyState icon={MessagesSquare} title="Elige una conversación"
        description="La IA responde sola las consultas de catálogo, precios y stock. Tú tomas las que necesitan a una persona."/></Card>}
  </div>;
}

function Thread({store, chat, onBack, onChanged, version}: {store: string; chat: string; onBack: () => void; onChanged: () => void; version: number}) {
  const base = "stores/" + store + "/inbox/conversations";
  const q = "?chat=" + encodeURIComponent(chat);
  const {data, error, mutate} = useApiData<Conversation>(ownerApi.url(base + "/detail" + q), version);
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState("");
  const [handback, setHandback] = useState(false);
  const bottom = useRef<HTMLDivElement>(null);
  const count = data?.messages.length ?? 0;
  useEffect(() => { bottom.current?.scrollIntoView({block: "end"}); }, [count]);

  async function act(path: string, body?: unknown, method: "POST" | "PUT" = "POST", done?: string) {
    setBusy(true);
    try {
      mutate(await ownerApi.json<Conversation>(base + "/" + path + q, body, method));
      if (done) toast.success(done);
      onChanged();
    } catch (e) { toast.error(e instanceof ApiError ? e.message : "No se pudo completar la acción."); }
    finally { setBusy(false); }
  }

  async function reply(event: FormEvent) {
    event.preventDefault();
    const message = text.trim();
    if (!message) return;
    setBusy(true);
    try {
      // Tomar la conversación al responder: la IA no contesta encima de una persona.
      if (data?.row.aiEnabled && data.row.status !== "ESCALATED") mutate(await ownerApi.json<Conversation>(base + "/take" + q));
      const res = await ownerApi.json<{status: string}>("stores/" + store + "/whatsapp/replies/" + crypto.randomUUID(), {chat, text: message}, "PUT");
      if (res.status === "REJECTED") toast.error("WhatsApp rechazó el mensaje. Revisa la conexión del número.");
      setText("");
      mutate(await ownerApi.get<Conversation>(base + "/detail" + q));
      onChanged();
    } catch (e) { toast.error(e instanceof ApiError && e.status === 409 ? "Conecta el número de WhatsApp antes de responder." : "No se pudo enviar."); }
    finally { setBusy(false); }
  }

  if (error) return <Alert variant="destructive">{error.message}</Alert>;
  if (!data) return <Card className="grid gap-3 p-4">{Array.from({length: 4}, (_, i) => <Skeleton key={i} className="h-12"/>)}</Card>;
  const r = data.row;
  return <Card className="flex min-h-[32rem] flex-col p-0">
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
    {r.status === "ESCALATED" && (data.escalationReason || data.summary) && <div className="border-b bg-warning/10 px-4 py-3 text-sm">
      <p className="font-medium">{data.escalationReason}</p>{data.summary && <p className="mt-0.5 text-muted-foreground">{data.summary}</p>}
      {r.overdue && <p className="mt-1 flex items-center gap-1 font-medium text-destructive"><AlarmClock className="size-4"/>Pasó el tiempo de respuesta acordado.</p>}
    </div>}
    <ol className="flex-1 space-y-2 overflow-y-auto bg-muted/30 px-4 py-4" aria-label="Mensajes">
      {data.messages.map((m, i) => <li key={i} className={cn("flex", m.author !== "CUSTOMER" && "justify-end")}>
        <div className={cn("max-w-[80%] rounded-2xl px-3 py-2 text-sm shadow-sm", m.author === "CUSTOMER" ? "rounded-bl-sm bg-card" :
          m.author === "AI" ? "rounded-br-sm bg-accent text-accent-foreground" : "rounded-br-sm bg-primary text-primary-foreground")}>
          <p className="whitespace-pre-wrap break-words">{m.body}</p>
          <p className="mt-1 flex items-center justify-end gap-1 text-[11px] opacity-70">
            {m.author === "AI" && <><Bot className="size-3"/>IA · </>}{m.author === "HUMAN" && "Equipo · "}{when(m.at)}
            {m.status === "READ" && " · Leído"}{m.status === "REJECTED" && " · No enviado"}</p>
        </div>
      </li>)}
      <div ref={bottom}/>
    </ol>
    <form onSubmit={reply} className="flex gap-2 border-t p-3">
      <label className="sr-only" htmlFor="reply">Responder</label>
      <textarea id="reply" value={text} onChange={e => setText(e.target.value)} maxLength={4000} rows={1} placeholder="Escribe una respuesta…"
        onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit(); } }}
        className="min-h-10 flex-1 resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm"/>
      <Button type="submit" disabled={busy || !text.trim()} aria-label="Enviar"><Send/></Button>
    </form>
    <HandbackDialog open={handback} onClose={() => setHandback(false)} busy={busy}
      onConfirm={note => act("handback", {note}, "POST", "La IA vuelve a atender esta conversación").then(() => setHandback(false))}/>
  </Card>;
}

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
