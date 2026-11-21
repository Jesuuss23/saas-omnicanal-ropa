"use client";

import {useEffect, useState} from "react";

type DocumentRow = {
  sale:string; created:string; kind:string; total:number; customer:string|null;
  status:string; series:string|null; number:number|null; hasXml:boolean; hasCdr:boolean;
};
type Page = {documents:DocumentRow[]; more:boolean};
type Props = {store:string; salesRevision:number; send:(path:string,body:string)=>Promise<Response>};
const statusLabels:Record<string,string> = {PENDING:"Pendiente de emisión", BETA_REVIEW:"Pendiente de confirmar con el proveedor", BETA_ACCEPTED:"Aceptado en beta", BETA_REJECTED:"Rechazado en beta"};
const money = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function FiscalRequests({store,salesRevision,send}:Props) {
  const path = "stores/"+store+"/billing/pos-documents";
  const [open,setOpen] = useState(false);
  const [page,setPage] = useState(0);
  const [refresh,setRefresh] = useState(0);
  const [data,setData] = useState<Page|null>(null);
  const [loadError,setLoadError] = useState(false);
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);

  useEffect(()=>{
    if (!open) return;
    const controller = new AbortController();
    setData(null);
    setLoadError(false);
    async function load() {
      try {
        const res = await fetch("/api/owner/"+path+"?page="+page,{cache:"no-store",signal:controller.signal});
        if (!res.ok) throw new Error();
        const result:Page = await res.json();
        if (!controller.signal.aborted) setData(result);
      } catch {
        if (!controller.signal.aborted) setLoadError(true);
      }
    }
    void load();
    return ()=>controller.abort();
  },[open,path,page,refresh,salesRevision]);

  async function download(sale:string, kind:"xml"|"cdr") {
    setBusy(true); setMessage("");
    try {
      const res = await fetch("/api/owner/"+path+"/"+sale+"/files/"+kind,{cache:"no-store"});
      if (res.status===404) throw new Error("El archivo ya no está disponible. Actualiza la lista.");
      if (res.status===422) throw new Error("El archivo guardado tiene un formato inválido.");
      if (!res.ok) throw new Error("No se pudo descargar el archivo.");
      const file:{filename:string; content:string} = await res.json();
      const bytes = Uint8Array.from(atob(file.content), char=>char.charCodeAt(0));
      const url = URL.createObjectURL(new Blob([bytes],{type:kind==="xml"?"application/xml":"application/zip"}));
      const link = document.createElement("a");
      link.href = url;
      link.download = file.filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url),1000);
      setMessage("Descarga iniciada: "+file.filename);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "No se pudo descargar el archivo.");
    } finally {setBusy(false);}
  }

  async function assign(sale:string) {
    setBusy(true); setMessage("");
    try {
      const res = await send(path+"/"+sale+"/number","{}");
      if (res.status===409) throw new Error("Verifica que el RUC coincida con la empresa beta registrada y que tenga un token guardado.");
      if (res.status===422) throw new Error("La serie de pruebas agotó sus correlativos.");
      if (!res.ok) throw new Error("No se pudo confirmar el número. Actualiza la lista o reintenta la misma venta.");
      const assigned = await res.json();
      setMessage("Número de pruebas asignado: "+assigned.series+"-"+String(assigned.number).padStart(8,"0")+".");
      setRefresh(value=>value+1);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Revisa la conexión y vuelve a consultar la venta.");
    } finally {setBusy(false);}
  }

  async function emit(sale:string) {
    setBusy(true); setMessage("");
    try {
      const res = await send(path+"/"+sale+"/send","{}");
      if (res.status===412) throw new Error("No se pudo verificar el ambiente beta y el token de esta empresa.");
      if (res.status===409) throw new Error("Revisa la numeración, el total de la venta y el RUC del emisor.");
      if (!res.ok) throw new Error("No se pudo confirmar el envío. Consulta el estado de la solicitud.");
      const result = await res.json();
      setMessage(statusLabels[result.status] || result.status);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "No se pudo confirmar el envío.");
    } finally {
      setBusy(false);
      setRefresh(value=>value+1);
    }
  }

  return <details className="mt-6 rounded-lg border p-4" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">Solicitudes de boletas y facturas del POS</summary>
    <div className="mt-3 flex items-center justify-between gap-2">
      <span>Numeración de pruebas</span>
      <button type="button" className="underline" disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Actualizar</button>
    </div>
    {loadError ? <p role="alert">No se pudo cargar la lista.</p> : !data ? <p>Cargando solicitudes…</p> :
      data.documents.length===0 ? <p>No hay solicitudes en esta página.</p> :
      <ul className="divide-y">{data.documents.map(row=><li key={row.sale} className="py-3">
        <strong>{row.kind==="FACTURA"?"Factura":"Boleta"} · {money.format(row.total)}</strong>
        <p>{row.customer || "Venta sin identificación"} · {date.format(new Date(row.created))}</p>
        <p className="break-all text-xs">Venta {row.sale}</p>
        <p>{statusLabels[row.status] || row.status}</p>
        {row.number!==null ? <p>Serie beta {row.series}-{String(row.number).padStart(8,"0")}</p> :
          row.status==="PENDING" && <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>assign(row.sale)}>Asignar número de pruebas</button>}
        {row.number!==null && row.status==="PENDING" && <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>emit(row.sale)}>Enviar a APIsPERU en beta</button>}
        {(row.hasXml || row.hasCdr) && <div className="mt-2 flex gap-4">
          {row.hasXml && <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>download(row.sale,"xml")}>Descargar XML</button>}
          {row.hasCdr && <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>download(row.sale,"cdr")}>Descargar CDR (.zip)</button>}
        </div>}
      </li>)}</ul>}
    <p role="status">{message}</p>
    <nav aria-label="Páginas de solicitudes" className="mt-3 flex gap-3">
      {page>0 && <button type="button" disabled={busy} onClick={()=>setPage(v=>v-1)}>Anterior</button>}
      {data?.more && <button type="button" disabled={busy} onClick={()=>setPage(v=>v+1)}>Siguiente</button>}
    </nav>
  </details>;
}
