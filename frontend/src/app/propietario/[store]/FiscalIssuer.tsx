"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect, useState, type FormEvent} from "react";

type Issuer = {ruc:string; legalName:string; address:string; ubigeo:string; revision:number};
type Props = {store:string; send:Send};
const input = "mt-1 block w-full rounded-lg border border-border px-3 py-2";

export default function FiscalIssuer({store,send}:Props) {
  const [issuer,setIssuer] = useState<Issuer|null>(null);
  const [reload,setReload] = useState(0);
  const [busy,setBusy] = useState(false);
  const [retry,setRetry] = useState(false);
  const [message,setMessage] = useState("Cargando datos fiscales…");
  const path = "stores/"+store+"/billing/issuer";

  useEffect(()=>{
    const controller = new AbortController();
    async function load() {
      try {
        const res = await apiFetch(ownerApi.url(path),{signal:controller.signal});
        if (!res.ok) throw new Error();
        const profile:Issuer = await res.json();
        if (!controller.signal.aborted) {setIssuer(profile); setMessage("");}
      } catch {
        if (!controller.signal.aborted) {setMessage("No se pudieron cargar los datos fiscales."); setRetry(true);}
      }
    }
    void load();
    return ()=>controller.abort();
  },[path,reload]);

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!issuer || busy || retry) return;
    setBusy(true); setMessage("");
    try {
      const res = await send(path,JSON.stringify(issuer),"PUT");
      if (res.status===409) {
        setRetry(true); setMessage("La ficha cambió en otra sesión. Recárgala antes de editar.");
        return;
      }
      if (!res.ok) throw new Error();
      const saved:Issuer = await res.json();
      setIssuer(saved); setMessage("Datos del emisor guardados.");
    } catch {setMessage("No se pudo confirmar el guardado. Recarga la ficha para verificar."); setRetry(true);}
    finally {setBusy(false);}
  }

  function change(key:keyof Omit<Issuer,"revision">, value:string) {
    setIssuer(current=>current && {...current,[key]:value});
  }

  return <section className="rounded-xl border bg-card p-5 shadow-card" aria-labelledby="issuer-title">
    <h2 id="issuer-title" className="font-semibold">Facturación electrónica · Datos del emisor</h2>
    {issuer && <form onSubmit={save}>
      <fieldset disabled={busy || retry} className="mt-4 grid gap-4 sm:grid-cols-2">
        <label>RUC<input className={input} value={issuer.ruc} onChange={e=>change("ruc",e.target.value)} inputMode="numeric" pattern="[0-9]{11}" maxLength={11} required/></label>
        <label>Razón social<input className={input} value={issuer.legalName} onChange={e=>change("legalName",e.target.value)} maxLength={200} required/></label>
        <label>Domicilio fiscal<input className={input} value={issuer.address} onChange={e=>change("address",e.target.value)} maxLength={250} required/></label>
        <label>Ubigeo<input className={input} value={issuer.ubigeo} onChange={e=>change("ubigeo",e.target.value)} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required/></label>
        <button className="rounded-lg bg-primary px-4 py-2 text-white">Guardar datos del emisor</button>
      </fieldset>
    </form>}
    <p role="status" className="mt-2 text-sm">{message}</p>
    {retry && <button className="text-primary underline" onClick={()=>{setIssuer(null); setRetry(false); setMessage("Cargando datos fiscales…"); setReload(v=>v+1);}}>Recargar ficha</button>}
  </section>;
}
