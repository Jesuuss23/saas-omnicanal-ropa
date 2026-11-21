"use client";

import {useEffect,useRef,useState} from "react";

type Props = {store:string; send:(path:string,body:string)=>Promise<Response>};
type Connection = {state:string; qr:string|null};

const labels:Record<string,string> = {
  CONNECTED:"WhatsApp conectado", DISCONNECTED:"WhatsApp desconectado",
  UNLINKED:"Sin vincular", PAIRING:"Esperando vinculación",
};

function problem(status:number) {
  if (status===503) return "Falta configurar Evolution API en el servidor.";
  if (status===409) return "La tienda ya tiene otra instancia asignada. Revisa la configuración.";
  if (status===403 || status===401) return "Vuelve a iniciar sesión como propietario.";
  return "No se pudo consultar Evolution API. Intenta nuevamente.";
}

export default function StoreWhatsApp({store,send}:Props) {
  const path = "stores/"+store+"/whatsapp";
  const [open,setOpen] = useState(false);
  const [channel,setChannel] = useState<Connection|null>(null);
  const [error,setError] = useState("");
  const [refresh,setRefresh] = useState(0);
  const [busy,setBusy] = useState(false);
  const hasQr = Boolean(channel?.qr);
  const generation = useRef(0);
  useEffect(()=>{
    if (!open || busy) return;
    const id = ++generation.current;
    const controller = new AbortController();
    let timer:ReturnType<typeof setTimeout>;
    async function check() {
      try {
        const res = await fetch("/api/owner/"+path,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error(problem(res.status));
        const result:Connection = await res.json();
        if (controller.signal.aborted || id!==generation.current) return;
        setChannel(previous=>({...result,qr:result.state==="CONNECTED" || result.state==="UNLINKED" ? null : previous?.qr ?? null}));
        if (result.state==="PAIRING" || (hasQr && result.state==="DISCONNECTED")) timer=setTimeout(check,5000);
      } catch (err) {
        if (!controller.signal.aborted && id===generation.current) setError(err instanceof Error ? err.message : "Error de conexión.");
      }
    }
    void check();
    return ()=>{controller.abort();clearTimeout(timer);};
  },[open,busy,path,refresh,hasQr]);

  async function connect() {
    const id=++generation.current;
    setBusy(true);setError("");
    try {
      const res=await send(path+"/connect","{}");
      if (!res.ok) throw new Error(problem(res.status));
      const result:Connection=await res.json();
      if (id===generation.current) setChannel(result);
    } catch (err) {
      if (id===generation.current) setError(err instanceof Error ? err.message : "Error al vincular WhatsApp.");
    } finally {setBusy(false);}
  }

  return <details className="mt-6 rounded-xl border border-stone-200 p-5" onToggle={e=>{
    setOpen(e.currentTarget.open);
    if (!e.currentTarget.open) {generation.current++;setChannel(null);setError("");}
  }}>
    <summary className="cursor-pointer font-semibold">WhatsApp de la tienda</summary>
    <p role="status" className="my-3">{error || (channel ? labels[channel.state] : "Consultando conexión…")}</p>
    {channel?.qr && <div className="mb-4">
      <p>En el WhatsApp de la tienda, abre Dispositivos vinculados y escanea el código.</p>
      {/* The pairing QR is private and must bypass image optimizers and caches. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={channel.qr} alt="QR para vincular el WhatsApp de esta tienda" width={240} height={240}/>
    </div>}
    <div className="flex flex-wrap gap-4">
      {channel && channel.state!=="CONNECTED" && <button type="button" disabled={busy}
        className="rounded-lg bg-teal-800 px-4 py-2 text-white" onClick={connect}>
        {busy?"Conectando…":channel.qr?"Renovar QR":"Conectar WhatsApp"}</button>}
      <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>{setError("");setRefresh(v=>v+1);}}>Actualizar estado</button>
    </div>
  </details>;
}
