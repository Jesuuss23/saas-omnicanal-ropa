"use client";

import {useEffect,useState} from "react";
import StoreWhatsApp from "./StoreWhatsApp";
import CloudApiWhatsApp from "./CloudApiWhatsApp";

type Send = (path:string,body:string,method?:"POST"|"PUT")=>Promise<Response>;
type Provider = "EVOLUTION"|"CLOUD_API";
const names:Record<Provider,string> = {EVOLUTION:"Evolution API (QR)",CLOUD_API:"WhatsApp Cloud API (oficial)"};

/** WhatsApp de la tienda en dos categorías: conexión rápida (QR) y conexión oficial de Meta. */
export default function WhatsAppChannels({store,send}:{store:string;send:Send}) {
  const path = "stores/"+store+"/whatsapp/channel";
  const [active,setActive] = useState<Provider|null>(null);
  const [notice,setNotice] = useState("");
  const [busy,setBusy] = useState(false);
  useEffect(()=>{
    const controller = new AbortController();
    fetch("/api/owner/"+path,{cache:"no-store",signal:controller.signal}).then(async res=>{
      if (!res.ok) throw new Error();
      const data:{activeProvider:Provider} = await res.json();
      if (!controller.signal.aborted) setActive(data.activeProvider);
    }).catch(()=>{if (!controller.signal.aborted) setNotice("No se pudo consultar el canal activo.");});
    return ()=>controller.abort();
  },[path]);

  async function choose(provider:Provider) {
    if (busy || provider===active) return;
    setBusy(true);setNotice("");
    try {
      const res = await send(path,JSON.stringify({activeProvider:provider}),"PUT");
      if (!res.ok) throw new Error();
      setActive(provider);
      setNotice("Las respuestas del inbox ahora salen por "+names[provider]+". El historial de chats se conserva.");
    } catch {setNotice("No se pudo cambiar el canal.");}
    finally {setBusy(false);}
  }

  return <section className="mt-6 rounded-xl border border-stone-200 p-5">
    <h2 className="font-semibold">WhatsApp de la tienda</h2>
    <fieldset className="mt-3" disabled={busy || !active}>
      <legend className="text-sm text-stone-600">Canal para responder a tus clientes</legend>
      <div className="mt-2 flex flex-wrap gap-4">
        {(Object.keys(names) as Provider[]).map(p=><label key={p} className="flex items-center gap-2">
          <input type="radio" name={"wa-provider-"+store} checked={active===p} onChange={()=>void choose(p)}/>
          {names[p]}
        </label>)}
      </div>
    </fieldset>
    {notice && <p role="status" className="mt-2 text-sm">{notice}</p>}
    <StoreWhatsApp store={store} send={send}/>
    <CloudApiWhatsApp store={store} send={send}/>
  </section>;
}
