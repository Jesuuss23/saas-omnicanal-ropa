"use client";

import {deliveryLabels as labels} from "./deliveryLabels";
import {useEffect,useRef,useState,type FormEvent} from "react";

type Props={store:string; chat:string; send:(path:string,body:string,method:"PUT")=>Promise<Response>};
type Row={text:string;status:string;createdAt:string};
const date=new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function ManualReply({store,chat,send}:Props) {
  const path="stores/"+store+"/whatsapp/replies";
  const [open,setOpen]=useState(false);
  const [text,setText]=useState("");
  const [rows,setRows]=useState<Row[]>([]);
  const [refresh,setRefresh]=useState(0);
  const [error,setError]=useState(false);
  const [busy,setBusy]=useState(false);
  const [notice,setNotice]=useState("");
  const attempt=useRef<{id:string;text:string}|null>(null);
  const sending=useRef(false);
  useEffect(()=>{
    if (!open) return;
    const controller=new AbortController();
    async function load() {
      try {
        const res=await fetch("/api/owner/"+path+"?"+new URLSearchParams({chat}),{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const data:Row[]=await res.json();
        if (!controller.signal.aborted) {setRows(data);setError(false);}
      } catch {if (!controller.signal.aborted) setError(true);}
    }
    void load();
    return ()=>controller.abort();
  },[open,path,chat,refresh]);

  async function submit(e:FormEvent) {
    e.preventDefault();
    const body=text.trim();
    if (!body || sending.current) return;
    sending.current=true;setBusy(true);setNotice("");
    try {
      if (!attempt.current || attempt.current.text!==body) attempt.current={id:crypto.randomUUID(),text:body};
      const res=await send(path+"/"+attempt.current.id,JSON.stringify({chat,text:body}),"PUT");
      if (!res.ok) throw new Error();
      const result=await res.json();
      setNotice(labels[result.status] || "Consulta el historial.");
      if (result.status==="ACCEPTED" || result.status==="REJECTED") attempt.current=null;
      if (result.status==="ACCEPTED") setText("");
    } catch {setNotice("No se pudo confirmar el envío. Revisa el historial antes de redactar otro mensaje.");}
    finally {sending.current=false;setBusy(false);setRefresh(n=>n+1);}
  }

  return <details onToggle={e=>{e.stopPropagation();setOpen(e.currentTarget.open);}}>
    <summary className="cursor-pointer text-teal-800">Mensaje de seguimiento por WhatsApp</summary>
    {open && <>
      {/^[0-9]{5,15}@s\.whatsapp\.net$/.test(chat) ? <form onSubmit={submit} className="my-2 grid max-w-md gap-2">
        <label>Mensaje para {chat.split("@")[0]}<textarea value={text} onChange={e=>setText(e.target.value)}
          disabled={busy} maxLength={4000} required className="block w-full rounded border p-2"/></label>
        <button type="submit" disabled={busy || !text.trim()} className="rounded bg-teal-800 px-3 py-2 text-white">
          {busy?"Enviando…":"Enviar por WhatsApp"}</button>
      </form> : <p>Este contacto aún no tiene un número de teléfono identificable para el envío.</p>}
      <p role="status">{notice}</p>
      <button type="button" className="underline" onClick={()=>setRefresh(n=>n+1)}>Actualizar últimos envíos</button>
      {error ? <p role="alert">No se pudo consultar el historial.</p> : <ul className="divide-y">
        {rows.map((r,i)=><li key={r.createdAt+"-"+i} className="py-1"><p className="text-sm">{date.format(new Date(r.createdAt))} · {labels[r.status]}</p>
          <p className="whitespace-pre-wrap break-words">{r.text}</p></li>)}
      </ul>}
    </>}
  </details>;
}
