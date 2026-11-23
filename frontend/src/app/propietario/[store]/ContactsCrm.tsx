"use client";
import {ownerApi,type Send} from "@/lib/api";

import ChatHistory from "./ChatHistory";
import ManualReply from "./ManualReply";
import FollowUpLog from "./FollowUpLog";
import {reviewMessages} from "./reviewMessages";
import {useState} from "react";
import {useApiData} from "@/lib/useApiData";

const stages = {NEW:"Nuevo", IN_PROGRESS:"En proceso", WON:"Venta ganada", LOST:"Venta perdida"};
type Stage = keyof typeof stages;
type Contact = {chat:string; stage:Stage; revision:number; lastReceived:string; followUpDate:string|null; messages:number};
type Message = {id:string; text:string; receivedAt:string};
type Page = {contacts:Contact[]; more:boolean};
type Props = {store:string; send:Send};
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

function RecentMessages({path,chat,stage,apply,disabled}:{path:string;chat:string;stage:Stage;apply:(stage:Stage)=>void;disabled:boolean}) {
  const [retry,setRetry] = useState(0);
  const {data:messages,error} = useApiData<Message[]>(ownerApi.url(path+"/messages?"+new URLSearchParams({chat})),retry);
  const failed = error!==null;
  if (failed) return <p role="alert">No se pudieron cargar los mensajes. <button type="button" className="underline" onClick={()=>setRetry(n=>n+1)}>Reintentar</button></p>;
  if (!messages) return <p>Cargando mensajes…</p>;
  const review = reviewMessages(messages);
  return <><ol className="mt-2 space-y-2 border-l pl-3">{messages.map(m=><li key={m.id}>
    <time className="text-xs text-muted-foreground" dateTime={m.receivedAt}>{date.format(new Date(m.receivedAt))}</time>
    <p className="whitespace-pre-wrap break-words">{m.text}</p>
  </li>)}</ol>
    <div className="mt-3 rounded-lg bg-muted p-3">
      <h4 className="font-semibold">Señales comerciales</h4>
      {review ? <><p>{review.label}</p><blockquote className="my-2 border-l pl-2">{review.quote}</blockquote>
        <p>Etapa sugerida: {stages[review.stage]}.</p>
        {stage!==review.stage && <button type="button" disabled={disabled} className="mt-2 text-primary underline"
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
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState("");
  const [expanded,setExpanded]=useState("");
  const contacts=useApiData<Page>(open?ownerApi.url(path+"/contacts?page="+page+"&followUps="+followUps):null,refresh);
  const data=contacts.data;
  const failed=contacts.error!==null;

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

  return <details className="rounded-xl border bg-card p-5 shadow-card" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">CRM · Contactos de WhatsApp</summary>
    <label className="mt-3 block">Mostrar <select className="rounded border p-1" disabled={busy} value={followUps?"scheduled":"all"}
      onChange={e=>{setPage(0);setExpanded("");setFollowUps(e.target.value==="scheduled");}}>
      <option value="all">Todos los contactos</option><option value="scheduled">Agenda de seguimiento</option>
    </select></label>
    <button type="button" disabled={busy} onClick={()=>setRefresh(n=>n+1)} className="my-3 text-primary underline">Actualizar contactos</button>
    {failed ? <p role="alert">No se pudo cargar el CRM. Vuelve a actualizar los contactos.</p> : !data ? <p>Cargando contactos…</p> :
      data.contacts.length===0 ? <p>{followUps?"No hay seguimientos en esta página.":"Aún no hay contactos en esta página."}</p> :
      <ul className="divide-y">{data.contacts.map(contact=><li key={contact.chat} className="space-y-2 py-3">
        <strong>{contact.chat.split("@")[0]}</strong>
        <p className="text-sm text-muted-foreground">{contact.messages} mensajes · Último contacto {date.format(new Date(contact.lastReceived))}</p>
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
          <button type="submit" disabled={busy} className="text-primary underline">Guardar fecha</button>
          {contact.followUpDate && <button type="button" disabled={busy} className="underline"
            onClick={()=>schedule(contact,null)}>Retirar de la agenda</button>}
        </form>
        <ChatHistory path={path} chat={contact.chat}/>
        <ManualReply store={store} chat={contact.chat} send={send}/>
        <FollowUpLog path={path} contact={contact} send={send} disabled={busy} onSaved={()=>{
          setNotice("Resultado registrado. El seguimiento salió de la agenda.");
          setRefresh(n=>n+1);
        }}/>
        <button type="button" aria-expanded={expanded===contact.chat} className="text-primary underline"
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
