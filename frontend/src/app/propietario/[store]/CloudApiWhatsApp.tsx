"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useEffect,useState,type FormEvent} from "react";

type Channel = {
  configured:boolean; phoneNumberId:string|null; wabaId:string|null; accessTokenConfigured:boolean;
  appSecretConfigured:boolean; displayPhoneNumber:string|null; verifiedName:string|null; qualityRating:string|null;
  status:string; lastError:string|null; revision:number; webhookUrl:string|null; webhookVerifyToken:string|null;
  encryptionReady:boolean;
};
type Template = {id:string;name:string;language:string;category:string|null;status:string;body:string|null;bodyParameters:number};
type TestMessage = {id:string;recipient:string;kind:string;templateName:string|null;status:string;error:string|null;createdAt:string};
type SendResult = {status:string;providerMessageId:string|null;error:string|null};

const statusLabels:Record<string,string> = {
  NOT_CONFIGURED:"Sin configurar", PENDING:"Credenciales guardadas, falta verificar",
  VERIFIED:"Conectado y verificado", INVALID:"Meta rechazó las credenciales",
};

async function problem(res:Response, fallback:string) {
  try { const body = await res.json(); if (body?.message) return body.message as string; } catch {}
  if (res.status===503) return "Falta configurar el cifrado de secretos en el servidor.";
  if (res.status===401 || res.status===403) return "Vuelve a iniciar sesión como propietario.";
  return fallback;
}

/** Categoría Cloud API: credenciales de Meta, plantillas de la WABA y entorno de prueba. */
export default function CloudApiWhatsApp({store,send}:{store:string;send:Send}) {
  const base = "stores/"+store+"/whatsapp/cloud";
  const [open,setOpen] = useState(false);
  const [channel,setChannel] = useState<Channel|null>(null);
  const [templates,setTemplates] = useState<Template[]>([]);
  const [tests,setTests] = useState<TestMessage[]>([]);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);
  const [version,setVersion] = useState(0);
  const [kind,setKind] = useState<"TEMPLATE"|"TEXT">("TEMPLATE");
  const [selected,setSelected] = useState("");

  useEffect(()=>{
    if (!open) return;
    const controller = new AbortController();
    const get = (p:string)=>apiFetch(ownerApi.url(base+p),{signal:controller.signal}).then(r=>{if(!r.ok) throw new Error(); return r.json();});
    Promise.all([get(""),get("/templates"),get("/test-messages")]).then(([c,t,m])=>{
      if (controller.signal.aborted) return;
      setChannel(c); setTemplates(t); setTests(m);
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo cargar la conexión con Cloud API.");});
    return ()=>controller.abort();
  },[open,base,version]);

  async function run(action:()=>Promise<Response>, fallback:string, onOk?:(res:Response)=>Promise<void>) {
    setBusy(true);setNotice("");
    try {
      const res = await action();
      if (!res.ok) {setNotice(await problem(res,fallback)); return;}
      if (onOk) await onOk(res);
      setVersion(v=>v+1);
    } catch {setNotice(fallback);}
    finally {setBusy(false);}
  }

  function save(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const body = {phoneNumberId:String(form.get("phoneNumberId")),wabaId:String(form.get("wabaId")),
      accessToken:String(form.get("accessToken")||""),appSecret:String(form.get("appSecret")||""),revision:channel?.revision ?? 0};
    const target = e.currentTarget;
    void run(()=>send(base,JSON.stringify(body),"PUT"),"No se pudieron guardar las credenciales.",async()=>{
      target.querySelectorAll<HTMLInputElement>("input[type=password]").forEach(i=>{i.value="";});
      const verified = await send(base+"/verify","{}");
      if (verified.ok) {
        const data:Channel = await verified.json();
        setNotice(data.status==="VERIFIED" ? "Credenciales verificadas con Meta." : (data.lastError ?? "Meta rechazó las credenciales."));
      }
    });
  }

  function test(e:FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const template = templates.find(t=>t.id===selected);
    const parameters = template ? Array.from({length:template.bodyParameters},(_,i)=>String(form.get("p"+i)||"")) : [];
    const body = kind==="TEMPLATE"
      ? {to:String(form.get("to")),kind,templateName:template?.name,language:template?.language,parameters}
      : {to:String(form.get("to")),kind,text:String(form.get("text")||"")};
    void run(()=>send(base+"/test-message",JSON.stringify(body)),"No se pudo enviar el mensaje de prueba.",async res=>{
      const result:SendResult = await res.json();
      setNotice(result.status==="ACCEPTED" ? "Meta aceptó el mensaje. Revisa el WhatsApp de destino."
        : "Meta no aceptó el mensaje: "+(result.error ?? "sin detalle"));
    });
  }

  const approved = templates.filter(t=>t.status==="APPROVED");
  const current = templates.find(t=>t.id===selected);

  return <details className="mt-4 rounded-xl border border-stone-200 p-5" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">Conexión oficial (WhatsApp Cloud API)</summary>
    <p className="mt-2 text-sm text-stone-600">Los identificadores y el token están en el panel de Meta para desarrolladores, dentro de tu app, en WhatsApp → Configuración de la API.</p>
    {notice && <p role="status" className="my-3">{notice}</p>}
    {!channel ? <p className="my-3">Cargando…</p> : <>
      <p className="my-3 font-medium">{statusLabels[channel.status] ?? channel.status}
        {channel.displayPhoneNumber && ` · ${channel.displayPhoneNumber}`}{channel.verifiedName && ` (${channel.verifiedName})`}</p>
      {channel.status==="INVALID" && channel.lastError && !notice && <p className="text-sm text-red-700">{channel.lastError}</p>}
      {!channel.encryptionReady && <p className="text-sm text-red-700">El servidor no tiene configurada la clave de cifrado de secretos; no se pueden guardar tokens.</p>}

      <form onSubmit={save} className="grid gap-3 md:grid-cols-2">
        <label className="grid gap-1 text-sm">Id del número de teléfono
          <input name="phoneNumberId" required inputMode="numeric" pattern="[0-9]{5,32}" defaultValue={channel.phoneNumberId ?? ""} className="rounded border px-3 py-2"/></label>
        <label className="grid gap-1 text-sm">Id de la cuenta de WhatsApp Business (WABA)
          <input name="wabaId" required inputMode="numeric" pattern="[0-9]{5,32}" defaultValue={channel.wabaId ?? ""} className="rounded border px-3 py-2"/>
          <span className="text-stone-500">Necesario para sincronizar las plantillas aprobadas.</span></label>
        <label className="grid gap-1 text-sm">Token de acceso
          <input name="accessToken" type="password" autoComplete="off" required={!channel.accessTokenConfigured}
            placeholder={channel.accessTokenConfigured ? "Guardado. Déjalo vacío para conservarlo." : ""} className="rounded border px-3 py-2"/>
          <span className="text-stone-500">Token permanente de un usuario del sistema con permisos whatsapp_business_messaging y whatsapp_business_management.</span></label>
        <label className="grid gap-1 text-sm">Clave secreta de la app
          <input name="appSecret" type="password" autoComplete="off"
            placeholder={channel.appSecretConfigured ? "Guardada. Déjala vacía para conservarla." : ""} className="rounded border px-3 py-2"/>
          <span className="text-stone-500">Se usa para comprobar la firma X-Hub-Signature-256 de cada webhook. Sin ella no se reciben mensajes.</span></label>
        <div className="flex flex-wrap gap-3 md:col-span-2">
          <button disabled={busy || !channel.encryptionReady} className="rounded-lg bg-teal-800 px-4 py-2 text-white">Guardar y verificar</button>
          {channel.configured && <button type="button" disabled={busy} className="text-teal-800 underline"
            onClick={()=>void run(()=>send(base+"/verify","{}"),"No se pudo verificar.")}>Volver a verificar</button>}
        </div>
      </form>

      {channel.configured && <div className="mt-4 rounded bg-stone-50 p-3 text-sm">
        <p className="font-medium">Webhook para tu app de Meta (WhatsApp → Configuración)</p>
        <p>URL de devolución de llamada: <code className="break-all">{channel.webhookUrl ?? "Configura PUBLIC_API_BASE_URL en el servidor"}</code></p>
        <p>Token de verificación: <code className="break-all">{channel.webhookVerifyToken}</code></p>
        <p>Suscribe el campo <code>messages</code>.</p>
      </div>}

      {channel.configured && <section className="mt-5">
        <div className="flex items-center gap-3">
          <h3 className="font-semibold">Plantillas de la cuenta ({templates.length})</h3>
          <button type="button" disabled={busy} className="text-teal-800 underline"
            onClick={()=>void run(()=>send(base+"/templates/sync","{}"),"No se pudieron sincronizar las plantillas.")}>Sincronizar con Meta</button>
        </div>
        {templates.length===0 ? <p className="text-sm text-stone-600">Aún no hay plantillas sincronizadas.</p> :
          <ul className="mt-2 divide-y text-sm">{templates.map(t=><li key={t.id} className="py-2">
            <span className="font-medium">{t.name}</span> · {t.language} · {t.category ?? "—"} · <span className={t.status==="APPROVED"?"text-teal-800":"text-stone-500"}>{t.status}</span>
            {t.body && <p className="text-stone-600">{t.body}</p>}
          </li>)}</ul>}
      </section>}

      {channel.configured && <section className="mt-5">
        <h3 className="font-semibold">Entorno de prueba</h3>
        <p className="text-sm text-stone-600">Fuera de la ventana de 24 h, WhatsApp solo permite plantillas aprobadas. Con un número de prueba de Meta, el destino debe estar en la lista de destinatarios permitidos.</p>
        <form onSubmit={test} className="mt-2 grid gap-3 md:grid-cols-2">
          <label className="grid gap-1 text-sm">Número de destino
            <input name="to" required placeholder="51987654321" inputMode="tel" className="rounded border px-3 py-2"/></label>
          <label className="grid gap-1 text-sm">Tipo de mensaje
            <select value={kind} onChange={e=>setKind(e.target.value as "TEMPLATE"|"TEXT")} className="rounded border px-3 py-2">
              <option value="TEMPLATE">Plantilla aprobada</option><option value="TEXT">Texto libre</option>
            </select></label>
          {kind==="TEMPLATE" ? <>
            <label className="grid gap-1 text-sm md:col-span-2">Plantilla
              <select required value={selected} onChange={e=>setSelected(e.target.value)} className="rounded border px-3 py-2">
                <option value="">Elige una plantilla</option>
                {approved.map(t=><option key={t.id} value={t.id}>{t.name} ({t.language})</option>)}
              </select></label>
            {current && Array.from({length:current.bodyParameters},(_,i)=><label key={current.id+i} className="grid gap-1 text-sm">
              {"Parámetro {{"+(i+1)+"}}"}<input name={"p"+i} required className="rounded border px-3 py-2"/></label>)}
          </> : <label className="grid gap-1 text-sm md:col-span-2">Mensaje
            <textarea name="text" required maxLength={4096} rows={3} className="rounded border px-3 py-2"/></label>}
          <div className="md:col-span-2"><button disabled={busy} className="rounded-lg bg-teal-800 px-4 py-2 text-white">Enviar prueba</button></div>
        </form>
        {tests.length>0 && <ul className="mt-3 text-sm">{tests.map(m=><li key={m.id}>
          {new Date(m.createdAt).toLocaleString("es-PE",{timeZone:"America/Lima"})} · {m.recipient} · {m.templateName ?? "texto"} · {m.status}{m.error && ` · ${m.error}`}
        </li>)}</ul>}
      </section>}

      {channel.configured && <button type="button" disabled={busy} className="mt-5 text-sm text-red-700 underline"
        onClick={()=>{ if (confirm("¿Desconectar Cloud API? Se borrarán las credenciales guardadas.")) void run(()=>send(base,undefined,"DELETE"),"No se pudo desconectar."); }}>
        Desconectar Cloud API</button>}
    </>}
  </details>;
}
