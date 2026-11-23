"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState} from "react";

type Message = {chat:string; id:string; text:string; receivedAt:string};
type Props = {path:string; connected:boolean; send:Send};
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function WhatsAppInbox({path,connected,send}:Props) {
  const [rows,setRows] = useState<Message[]|null>(null);
  const [refresh,setRefresh] = useState(0);
  const [failed,setFailed] = useState(false);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);

  useEffect(()=>{
    const controller = new AbortController();
    async function load() {
      setFailed(false);
      try {
        const res = await apiFetch(ownerApi.url(path+"/inbox"),{signal:controller.signal});
        if (!res.ok) throw new Error();
        const messages:Message[] = await res.json();
        if (!controller.signal.aborted) setRows(messages);
      } catch {
        if (!controller.signal.aborted) setFailed(true);
      }
    }
    void load();
    return ()=>controller.abort();
  },[path,refresh]);

  async function enable() {
    setBusy(true); setNotice("");
    try {
      const res = await send(path+"/inbox/enable","{}");
      if (!res.ok) throw new Error(res.status===503 ? "Falta configurar el webhook en el servidor." : "No se pudo configurar el webhook en Evolution API.");
      setNotice("Recepción de mensajes activada.");
    } catch (err) {setNotice(err instanceof Error ? err.message : "No se pudo activar la recepción.");}
    finally {setBusy(false);}
  }

  return <section className="mt-5 border-t pt-4">
    <h3 className="font-semibold">Últimos mensajes recibidos</h3>
    {connected && <details className="my-2" onToggle={event=>event.stopPropagation()}>
      <summary className="cursor-pointer">Configurar recepción</summary>
      <p className="my-2 text-sm">Esta acción configura el webhook de esta instancia para recibir mensajes y reemplaza su configuración anterior.</p>
      <button type="button" disabled={busy} onClick={enable} className="rounded bg-primary px-3 py-2 text-white">Activar recepción</button>
    </details>}
    <p role="status">{notice}</p>
    <button type="button" onClick={()=>setRefresh(v=>v+1)} className="my-2 text-primary underline">Actualizar mensajes</button>
    {failed ? <p role="alert">No se pudo consultar la bandeja.</p> : rows===null ? <p>Cargando mensajes…</p> :
      rows.length===0 ? <p>Aún no hay mensajes de texto recibidos.</p> :
      <ul className="divide-y">{rows.map(row=><li key={row.chat+":"+row.id} className="py-2">
        <strong>{row.chat.split("@")[0]}</strong> <time className="text-sm" dateTime={row.receivedAt}>{date.format(new Date(row.receivedAt))}</time>
        <p className="whitespace-pre-wrap break-words">{row.text}</p>
      </li>)}</ul>}
  </section>;
}
