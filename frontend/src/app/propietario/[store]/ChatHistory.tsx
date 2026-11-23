"use client";
import {apiFetch,ownerApi} from "@/lib/api";

import {useEffect,useState} from "react";
import {deliveryLabels} from "./deliveryLabels";

type Line = {key:string; text:string; at:string; status:string|null};
type Page = {lines:Line[]; more:boolean};
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function ChatHistory({path,chat}:{path:string;chat:string}) {
  const [open,setOpen]=useState(false);
  return <details onToggle={event=>{event.stopPropagation();setOpen(event.currentTarget.open);}}>
    <summary className="cursor-pointer text-primary">Conversación completa</summary>
    {open && <Timeline path={path} chat={chat}/>}
  </details>;
}

function Timeline({path,chat}:{path:string;chat:string}) {
  const [page,setPage]=useState<Page>({lines:[],more:false});
  const [cursor,setCursor]=useState<Line|null>(null);
  const [refresh,setRefresh]=useState(0);
  const [busy,setBusy]=useState(true);
  const [error,setError]=useState(false);
  useEffect(()=>{
    const controller=new AbortController();
    async function load() {
      setBusy(true);setError(false);
      try {
        const query=new URLSearchParams({chat});
        if (cursor) {
          query.set("before",cursor.at);
          query.set("beforeKey",cursor.key);
        }
        const res=await apiFetch(ownerApi.url(path+"/conversation?"+query),{signal:controller.signal});
        if (!res.ok) throw new Error();
        const data:Page=await res.json();
        if (!controller.signal.aborted) setPage(current=>({
          lines:cursor ? [...data.lines,...current.lines] : data.lines,more:data.more
        }));
      } catch {if (!controller.signal.aborted) setError(true);}
      finally {if (!controller.signal.aborted) setBusy(false);}
    }
    void load();
    return ()=>controller.abort();
  },[path,chat,cursor,refresh]);

  return <div className="my-3">
    <div className="flex gap-4">
      {page.more && !error && <button type="button" disabled={busy} className="underline"
        onClick={()=>setCursor(page.lines[0])}>Ver anteriores</button>}
      <button type="button" disabled={busy} className="underline" onClick={()=>{
        setCursor(null);setRefresh(n=>n+1);
      }}>Ver más recientes</button>
    </div>
    {error && <p role="alert">No se pudo cargar la conversación. <button type="button" className="underline" onClick={()=>setRefresh(n=>n+1)}>Reintentar</button></p>}
    {busy && <p role="status">Cargando conversación…</p>}
    {!busy && !error && !page.lines.length && <p>Aún no hay mensajes registrados.</p>}
    <ol className="mt-3 space-y-2">{page.lines.map(line=><li key={line.key}
      className={line.status ? "ml-auto max-w-[85%] rounded-lg bg-accent p-3" : "max-w-[85%] rounded-lg bg-muted p-3"}>
      <strong>{line.status ? "Tienda" : "Cliente"}</strong>
      <p className="whitespace-pre-wrap break-words">{line.text}</p>
      <p className="text-xs text-muted-foreground"><time dateTime={line.at}>{date.format(new Date(line.at))}</time>
        {line.status && " · "+(deliveryLabels[line.status] || line.status)}</p>
    </li>)}</ol>
  </div>;
}
