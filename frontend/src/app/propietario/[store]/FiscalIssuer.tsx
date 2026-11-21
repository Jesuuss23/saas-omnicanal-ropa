"use client";

import BetaCompany from "./BetaCompany";
import {useEffect, useState, type FormEvent} from "react";

type Issuer = {ruc:string; legalName:string; address:string; ubigeo:string; revision:number};
type Props = {store:string; send:(path:string, body:string, method:"PUT"|"POST")=>Promise<Response>};
const input = "mt-1 block w-full rounded-lg border border-stone-300 px-3 py-2";

export default function FiscalIssuer({store,send}:Props) {
  const [issuer,setIssuer] = useState<Issuer|null>(null);
  const [savedRuc,setSavedRuc] = useState("");
  const [password,setPassword] = useState("");
  const [reload,setReload] = useState(0);
  const [busy,setBusy] = useState(false);
  const [retry,setRetry] = useState(false);
  const [message,setMessage] = useState("");
  const path = "stores/"+store+"/billing/issuer";

  useEffect(()=>{
    const controller = new AbortController();
    setIssuer(null); setRetry(false); setMessage("Cargando datos fiscales…");
    async function load() {
      try {
        const res = await fetch("/api/owner/"+path,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const profile:Issuer = await res.json();
        if (!controller.signal.aborted) {setIssuer(profile); setSavedRuc(profile.ruc); setMessage("");}
      } catch {
        if (!controller.signal.aborted) {setMessage("No se pudieron cargar los datos fiscales."); setRetry(true);}
      }
    }
    void load();
    return ()=>controller.abort();
  },[path,reload]);

  async function verify() {
    if (!issuer || busy || retry) return;
    setBusy(true);
    setMessage("Consultando el RUC guardado en APIsPERU…");
    try {
      const res = await fetch("/api/owner/"+path+"/verification", {cache:"no-store"});
      if (res.ok) {
        const result:{ruc:string} = await res.json();
        setMessage("APIsPERU confirmó la empresa asignada para el RUC " + result.ruc + ".");
      } else {
        const errors:Record<number,string> = {
          409:"Revisa el RUC guardado y la empresa asignada a esta tienda.",
          424:"APIsPERU no encontró la empresa asignada a esta tienda.",
          503:"Falta configurar la conexión de esta tienda con APIsPERU.",
        };
        setMessage(errors[res.status] || "No se pudo verificar la empresa. Revisa la conexión y la vigencia del token.");
      }
    } catch {setMessage("No se pudo consultar APIsPERU. Intenta nuevamente.");}
    finally {setBusy(false);}
  }

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
      setIssuer(saved); setSavedRuc(saved.ruc); setMessage("Datos del emisor guardados.");
    } catch {setMessage("No se pudo confirmar el guardado. Recarga la ficha para verificar."); setRetry(true);}
    finally {setBusy(false);}
  }

  async function downloadCertificate(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!issuer || busy || retry) return;
    setBusy(true);
    setMessage("Generando el certificado de pruebas…");
    try {
      const res = await send(path+"/test-certificate",JSON.stringify({revision:issuer.revision,password}),"POST");
      if (res.status === 409) {
        setRetry(true);
        setMessage("La ficha fiscal cambió. Recárgala para usar el RUC vigente.");
        return;
      }
      if (res.status === 503) {
        setMessage("Falta configurar el acceso a APIsPERU en el servidor.");
        return;
      }
      if (!res.ok) throw new Error();
      const result:{ruc:string;pfx:string} = await res.json();
      const bytes = Uint8Array.from(atob(result.pfx), char => char.charCodeAt(0));
      const url = URL.createObjectURL(new Blob([bytes], {type:"application/x-pkcs12"}));
      const link = document.createElement("a");
      link.href = url;
      link.download = "pruebas-"+result.ruc+".pfx";
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url), 1000);
      setMessage("Descarga iniciada para el RUC "+result.ruc+".");
    } catch {
      setMessage("No se pudo descargar el certificado. Revisa la conexión con APIsPERU.");
    } finally {
      setPassword("");
      setBusy(false);
    }
  }

  function change(key:keyof Omit<Issuer,"revision">, value:string) {
    setIssuer(current=>current && {...current,[key]:value});
  }

  return <details className="mt-6 rounded-xl border border-stone-200 p-5">
    <summary className="cursor-pointer font-semibold">Facturación electrónica · Datos del emisor</summary>
    {issuer && <form onSubmit={save}>
      <fieldset disabled={busy || retry} className="mt-4 grid gap-4 sm:grid-cols-2">
        <label>RUC<input className={input} value={issuer.ruc} onChange={e=>change("ruc",e.target.value)} inputMode="numeric" pattern="[0-9]{11}" maxLength={11} required/></label>
        <label>Razón social<input className={input} value={issuer.legalName} onChange={e=>change("legalName",e.target.value)} maxLength={200} required/></label>
        <label>Domicilio fiscal<input className={input} value={issuer.address} onChange={e=>change("address",e.target.value)} maxLength={250} required/></label>
        <label>Ubigeo<input className={input} value={issuer.ubigeo} onChange={e=>change("ubigeo",e.target.value)} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required/></label>
        <button className="rounded-lg bg-teal-800 px-4 py-2 text-white">Guardar datos del emisor</button>
        {issuer.revision > 0 && <button type="button" className="rounded-lg border border-teal-800 px-4 py-2 text-teal-800" onClick={verify}>Verificar RUC guardado en APIsPERU</button>}
      </fieldset>
    </form>}
    {issuer && issuer.revision > 0 && <form onSubmit={downloadCertificate} className="mt-5 border-t pt-4">
      <fieldset disabled={busy || retry} className="grid max-w-md gap-3">
        <legend className="font-semibold">Certificado de pruebas para el RUC {savedRuc}</legend>
        <label>Elige una contraseña para proteger el archivo .pfx
          <input className={input} type="password" autoComplete="new-password" minLength={8} maxLength={128}
            required value={password} onChange={e=>setPassword(e.target.value)} />
        </label>
        <button className="rounded-lg border border-teal-800 px-4 py-2 text-teal-800">Descargar certificado de pruebas</button>
      </fieldset>
    </form>}
    {issuer && issuer.revision > 0 && <BetaCompany key={issuer.revision} path={path} ruc={savedRuc} revision={issuer.revision} send={send}/>}
    <p role="status" className="mt-2 text-sm">{message}</p>
    {retry && <button className="text-teal-800 underline" onClick={()=>setReload(v=>v+1)}>Recargar ficha</button>}
  </details>;
}
