"use client";

import {reviewMessages} from "./reviewMessages";
import {useEffect,useState} from "react";

const stages = {NEW:"Nuevo", IN_PROGRESS:"En proceso", WON:"Venta ganada", LOST:"Venta perdida"};
type Stage = keyof typeof stages;
type Contact = {chat:string; stage:Stage; revision:number; lastReceived:string; followUpDate:string|null; messages:number};
type Message = {id:string; text:string; receivedAt:string};
type Page = {contacts:Contact[]; more:boolean};
type Props = {store:string; send:(path:string,body:string,method:"PUT")=>Promise<Response>};
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

function RecentMessages({path,chat,stage,apply,disabled}:{path:string;chat:string;stage:Stage;apply:(stage:Stage)=>void;disabled:boolean}) {
  const [messages,setMessages] = useState<Message[]|null>(null);
  const [failed,setFailed] = useState(false);
  const [retry,setRetry] = useState(0);
  useEffect(()=>{
    const controller=new AbortController();
    setFailed(false);
    async function load() {
      try {
        const res=await fetch("/api/owner/"+path+"/messages?"+new URLSearchParams({chat}),{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const rows:Message[]=await res.json();
        if (!controller.signal.aborted) setMessages(rows);
      } catch {if (!controller.signal.aborted) setFailed(true);}
    }
    void load();
    return ()=>controller.abort();
  },[path,chat,retry]);
  if (failed) return <p role="alert">No se pudieron cargar los mensajes. <button type="button" className="underline" onClick={()=>setRetry(n=>n+1)}>Reintentar</button></p>;
  if (!messages) return <p>Cargando mensajes…</p>;
  const review = reviewMessages(messages);
  return <><ol className="mt-2 space-y-2 border-l pl-3">{messages.map(m=><li key={m.id}>
    <time className="text-xs text-stone-600" dateTime={m.receivedAt}>{date.format(new Date(m.receivedAt))}</time>
    <p className="whitespace-pre-wrap break-words">{m.text}</p>
  </li>)}</ol>
    <div className="mt-3 rounded-lg bg-stone-50 p-3">
      <h4 className="font-semibold">Señales comerciales</h4>
      {review ? <><p>{review.label}</p><blockquote className="my-2 border-l pl-2">{review.quote}</blockquote>
        <p>Etapa sugerida: {stages[review.stage]}.</p>
        {stage!==review.stage && <button type="button" disabled={disabled} className="mt-2 text-teal-800 underline"
          onClick={()=>apply(review.stage)}>Aplicar etapa sugerida</button>}
      </> : <p>Los mensajes recientes no contienen una señal clara para sugerir una etapa.</p>}
    </div></>;
}

export default function ContactsCrm({store,send}:Props) {
  const path="stores/"+store+"/crm";
  const [open,setOpen]=useState(false);
  const [followUps,setFollowUps]=useState(false);
  const [page,setPage]=useState(0);
  const [refresh,setRefresh]=useState(0);
  const [data,setData]=useState<Page|null>(null);
  const [failed,setFailed]=useState(false);
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState("");
  const [expanded,setExpanded]=useState("");
  useEffect(()=>{
    if (!open) return;
    const controller=new AbortController();
    setData(null); setFailed(false);
    async function load() {
      try {
        const res=await fetch("/api/owner/"+path+"/contacts?page="+page+"&followUps="+followUps,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const result:Page=await res.json();
        if (!controller.signal.aborted) setData(result);
      } catch {if (!controller.signal.aborted) setFailed(true);}
    }
    void load();
    return ()=>controller.abort();
  },[path,open,page,refresh,followUps]);

  async function change(contact:Contact,stage:Stage) {
    setBusy(true);setNotice("");
    try {
      const res=await send(path+"/stage",JSON.stringify({chat:contact.chat,revision:contact.revision,stage}),"PUT");
      if (res.status===409) throw new Error("La ficha cambió en otra sesión. Revisa la etapa actual.");
      if (!res.ok) throw new Error("No se pudo confirmar el cambio de etapa.");
      setNotice("Etapa guardada: "+stages[stage]+".");
    } catch (err) {setNotice(err instanceof Error ? err.message : "No se pudo guardar.");}
    finally {setBusy(false);setRefresh(n=>n+1);}
  }

  async function schedule(contact:Contact, date:string|null) {
    setBusy(true);setNotice("");
    try {
      const res=await send(path+"/follow-up",JSON.stringify({chat:contact.chat,revision:contact.revision,date}),"PUT");
      if (res.status===409) throw new Error("Otra sesión actualizó la ficha. Revisa los datos antes de continuar.");
      if (!res.ok) throw new Error("No se pudo confirmar el seguimiento.");
      setNotice(date ? "Fecha de seguimiento guardada." : "Seguimiento retirado de la agenda.");
    } catch (err) {setNotice(err instanceof Error ? err.message : "No se pudo guardar.");}
    finally {setBusy(false);setRefresh(n=>n+1);}
  }

  return <details className="mt-6 rounded-xl border border-stone-200 p-5" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">CRM · Contactos de WhatsApp</summary>
    <label className="mt-3 block">Mostrar <select className="rounded border p-1" disabled={busy} value={followUps?"scheduled":"all"}
      onChange={e=>{setPage(0);setExpanded("");setFollowUps(e.target.value==="scheduled");}}>
      <option value="all">Todos los contactos</option><option value="scheduled">Agenda de seguimiento</option>
    </select></label>
    <button type="button" disabled={busy} onClick={()=>setRefresh(n=>n+1)} className="my-3 text-teal-800 underline">Actualizar contactos</button>
    {failed ? <p role="alert">No se pudo cargar el CRM. Vuelve a actualizar los contactos.</p> : !data ? <p>Cargando contactos…</p> :
      data.contacts.length===0 ? <p>{followUps?"No hay seguimientos en esta página.":"Aún no hay contactos en esta página."}</p> :
      <ul className="divide-y">{data.contacts.map(contact=><li key={contact.chat} className="space-y-2 py-3">
        <strong>{contact.chat.split("@")[0]}</strong>
        <p className="text-sm text-stone-600">{contact.messages} mensajes · Último contacto {date.format(new Date(contact.lastReceived))}</p>
        <label className="block">Etapa comercial <select disabled={busy} className="rounded border p-1"
            value={contact.stage} onChange={e=>change(contact,e.target.value as Stage)}>
          {Object.entries(stages).map(([key,label])=><option key={key} value={key}>{label}</option>)}
        </select></label>
        <form className="flex flex-wrap items-end gap-2" onSubmit={e=>{
          e.preventDefault();
          const date=String(new FormData(e.currentTarget).get("due") || "");
          if (date) void schedule(contact,date);
        }}>
          <label>Fecha de seguimiento<input className="block rounded border p-1" type="date" name="due"
            defaultValue={contact.followUpDate || ""} required disabled={busy}/></label>
          <button type="submit" disabled={busy} className="text-teal-800 underline">Guardar fecha</button>
          {contact.followUpDate && <button type="button" disabled={busy} className="underline"
            onClick={()=>schedule(contact,null)}>Retirar de la agenda</button>}
        </form>
        <button type="button" aria-expanded={expanded===contact.chat} className="text-teal-800 underline"
          onClick={()=>setExpanded(expanded===contact.chat?"":contact.chat)}>
          {expanded===contact.chat?"Ocultar mensajes":"Ver los últimos 5 mensajes"}</button>
        {expanded===contact.chat && <RecentMessages path={path} chat={contact.chat} stage={contact.stage} disabled={busy} apply={stage=>void change(contact,stage)}/>}
      </li>)}</ul>}
    <p role="status">{notice}</p>
    <nav aria-label="Páginas de contactos" className="mt-3 flex gap-3">
      {page>0 && <button type="button" disabled={busy} onClick={()=>setPage(v=>v-1)}>Anterior</button>}
      {data?.more && <button type="button" disabled={busy} onClick={()=>setPage(v=>v+1)}>Siguiente</button>}
    </nav>
  </details>;
}
