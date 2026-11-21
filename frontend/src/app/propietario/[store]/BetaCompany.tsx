"use client";

import {useEffect, useState, type FormEvent} from "react";

type Props = {
  path:string; ruc:string; revision:number;
  send:(path:string, body:string, method:"POST")=>Promise<Response>;
};

const descriptions:Record<string,string> = {
  CONFIGURED:"Esta tienda ya tiene una empresa asignada en el servidor.",
  REGISTERED:"Empresa de pruebas registrada. Puedes verificar su RUC en la ficha fiscal.",
  REVIEW:"Hay un alta pendiente de confirmación. Consulta al administrador antes de volver a solicitarla.",
};

async function encodeFile(file:File) {
  if (!file.size || file.size > 204800) throw new Error("Cada archivo debe pesar como máximo 200 KiB y contener datos.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(""));
}

export default function BetaCompany({path,ruc,revision,send}:Props) {
  const endpoint = path+"/beta-company";
  const [status,setStatus] = useState("");
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);
  const [reload,setReload] = useState(0);

  useEffect(()=>{
    const controller = new AbortController();
    async function load() {
      try {
        const res = await fetch("/api/owner/"+endpoint,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const data = await res.json();
        if (!controller.signal.aborted) {setStatus(data.status);setMessage("");}
      } catch {
        if (!controller.signal.aborted) setMessage("No se pudo consultar el estado del alta.");
      }
    }
    void load();
    return ()=>controller.abort();
  },[endpoint,reload]);

  async function submit(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || status !== "AVAILABLE") return;
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true); setMessage("");
    try {
      const pfx = await encodeFile(data.get("pfx") as File);
      const logo = await encodeFile(data.get("logo") as File);
      const res = await send(endpoint,JSON.stringify({revision,password:data.get("password"),pfx,logo}),"POST");
      if (res.status === 409) throw new Error("La ficha cambió. Recarga la página antes de enviar.");
      if (res.status === 400 || res.status === 422) throw new Error("Revisa el certificado, la contraseña, el logo y los datos fiscales.");
      if (res.status === 503) throw new Error("Falta configurar el acceso a APIsPERU.");
      if (!res.ok) throw new Error("No se pudo confirmar el alta. Consulta su estado.");
      setStatus((await res.json()).status);
      form.reset();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "No se pudo confirmar el alta.");
    } finally {setBusy(false);}
  }

  return <section className="mt-5 border-t pt-4">
    <h3 className="font-semibold">Alta de empresa en beta</h3>
    {status === "AVAILABLE" && <form onSubmit={submit}>
      <fieldset disabled={busy} className="mt-2 grid max-w-md gap-3">
        <p className="text-sm">Registrar en APIsPERU el RUC {ruc} con los datos guardados y el usuario SOL de pruebas.</p>
        <label>Certificado de pruebas (.pfx o .p12)<input className="block" name="pfx" type="file" accept=".pfx,.p12" required/></label>
        <label>Contraseña del certificado<input className="block w-full rounded-lg border p-2" name="password" type="password" autoComplete="off" maxLength={128} required/></label>
        <label>Logo PNG (máximo 200 KiB)<input className="block" name="logo" type="file" accept="image/png" required/></label>
        <button className="rounded-lg bg-teal-800 px-4 py-2 text-white">{busy?"Registrando…":"Registrar empresa en beta"}</button>
      </fieldset>
    </form>}
    <p role="status" className="mt-2 text-sm">{message || descriptions[status] || (!status && "Consultando estado…")}</p>
    {(message || status === "REVIEW") && <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>setReload(value=>value+1)}>Consultar estado</button>}
  </section>;
}
