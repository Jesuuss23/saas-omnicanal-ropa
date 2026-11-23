"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useState,type FormEvent} from "react";

const outcomes = {CONTACTED:"Cliente contactado", NO_RESPONSE:"Sin respuesta", DECLINED:"Cliente declinó"};
type Entry = {revision:number; scheduledDate:string; completedAt:string; outcome:keyof typeof outcomes; note:string};
type Props = {
  path:string; contact:{chat:string;revision:number;followUpDate:string|null};
  send:Send;
  disabled:boolean; onSaved:()=>void;
};
const time = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function FollowUpLog({path,contact,send,disabled,onSaved}:Props) {
  const [entries,setEntries]=useState<Entry[]|null>(null);
  const [more,setMore]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  async function load(older:boolean) {
    setBusy(true);setError("");
    try {
      const query=new URLSearchParams({chat:contact.chat});
      if (older && entries?.length) query.set("before",String(entries[entries.length-1].revision));
      const res=await apiFetch(ownerApi.url(path+"/follow-up/history?"+query));
      if (!res.ok) throw new Error();
      const rows:Entry[]=await res.json();
      setEntries(previous=>older ? [...(previous || []),...rows] : rows);
      setMore(rows.length===10);
    } catch {setError("No se pudo cargar el historial.");}
    finally {setBusy(false);}
  }

  async function complete(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields=new FormData(event.currentTarget);
    setBusy(true);setError("");
    try {
      const res=await send(path+"/follow-up/complete",JSON.stringify({
        chat:contact.chat, revision:contact.revision, outcome:fields.get("outcome"), note:fields.get("note"),
      }),"PUT");
      if (res.status===409) throw new Error("La ficha cambió. Actualiza los contactos y revisa el historial.");
      if (!res.ok) throw new Error("No se pudo confirmar el resultado. Revisa el historial antes de reintentar.");
      onSaved();
    } catch (err) {setError(err instanceof Error ? err.message : "Revisa la conexión.");}
    finally {setBusy(false);}
  }

  return <div className="space-y-2">
    {contact.followUpDate && <details onToggle={e=>e.stopPropagation()}>
      <summary className="cursor-pointer text-primary">Registrar resultado del seguimiento</summary>
      <form onSubmit={complete} className="mt-2 grid max-w-md gap-2">
        <label>Resultado <select name="outcome" required defaultValue="" className="rounded border p-1">
          <option value="" disabled>Seleccionar</option>
          {Object.entries(outcomes).map(([key,label])=><option key={key} value={key}>{label}</option>)}
        </select></label>
        <label>Nota interna (opcional)<textarea name="note" maxLength={500} className="block w-full rounded border p-2"/></label>
        <button disabled={disabled || busy} className="rounded bg-primary px-3 py-2 text-white">Completar seguimiento</button>
      </form>
    </details>}
    <button type="button" disabled={busy} aria-expanded={entries!==null} className="text-primary underline"
      onClick={()=>entries===null ? void load(false) : setEntries(null)}>
      {entries===null?"Ver historial de seguimientos":"Ocultar historial"}</button>
    {entries && <div>
      {entries.length===0 ? <p>Aún no hay seguimientos completados.</p> : <ol className="space-y-2 border-l pl-3">
        {entries.map(entry=><li key={entry.revision}><strong>{outcomes[entry.outcome]}</strong>
          <p className="text-sm">Programado para {entry.scheduledDate} · Atendido {time.format(new Date(entry.completedAt))} (Lima)</p>
          {entry.note && <p className="whitespace-pre-wrap break-words">{entry.note}</p>}
        </li>)}
      </ol>}
      {more && <button type="button" disabled={busy} className="underline" onClick={()=>load(true)}>Ver anteriores</button>}
    </div>}
    {error && <p role="alert">{error}</p>}
  </div>;
}
