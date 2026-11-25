"use client";
import {apiFetch,errorMessage,ownerApi,type Send} from "@/lib/api";

import {useState} from "react";
import {useApiData} from "@/lib/useApiData";

type Submission = {
  attemptedAt:string; cdrCode:string|null; description:string|null; notes:string[];
  errorCode:string|null; errorMessage:string|null; httpStatus:number|null;
};

type DocumentRow = {
  submission:Submission|null; sale:string; created:string; kind:string; total:number; customer:string|null;
  status:string; series:string|null; number:number|null; hasXml:boolean; hasCdr:boolean; pdfUrl:string|null;
};
type Page = {documents:DocumentRow[]; more:boolean};
type Props = {store:string; salesRevision:number; send:Send};
const statusLabels:Record<string,string> = {PENDING:"Pendiente de emisión", BETA_REVIEW:"Pendiente de confirmar con Nubefact", BETA_ACCEPTED:"Aceptado por SUNAT", BETA_REJECTED:"Rechazado por SUNAT"};
const money = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const date = new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

export default function FiscalRequests({store,salesRevision,send}:Props) {
  const path = "stores/"+store+"/billing/pos-documents";
  const [open,setOpen] = useState(false);
  const [page,setPage] = useState(0);
  const [refresh,setRefresh] = useState(0);
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);

  const list = useApiData<Page>(open?ownerApi.url(path+"?page="+page):null, refresh+":"+salesRevision);
  const data = list.data;
  const loadError = list.error!==null;

  async function download(sale:string, kind:"xml"|"cdr") {
    setBusy(true); setMessage("");
    try {
      const res = await apiFetch(ownerApi.url(path+"/"+sale+"/files/"+kind));
      if (res.status===404) throw new Error("El archivo ya no está disponible. Actualiza la lista.");
      if (!res.ok) throw new Error(await errorMessage(res,"No se pudo descargar el archivo."));
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
      if (!res.ok) throw new Error(await errorMessage(res,"No se pudo confirmar el número. Actualiza la lista o reintenta la misma venta."));
      const assigned = await res.json();
      setMessage("Número asignado: "+assigned.series+"-"+String(assigned.number).padStart(8,"0")+".");
      setRefresh(value=>value+1);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Revisa la conexión y vuelve a consultar la venta.");
    } finally {setBusy(false);}
  }

  async function emit(sale:string) {
    setBusy(true); setMessage("");
    try {
      const res = await send(path+"/"+sale+"/send","{}");
      if (!res.ok) throw new Error(await errorMessage(res,"No se pudo confirmar el envío. Consulta el estado de la solicitud."));
      const result = await res.json();
      setMessage(statusLabels[result.status] || result.status);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "No se pudo confirmar el envío.");
    } finally {
      setBusy(false);
      setRefresh(value=>value+1);
    }
  }

  async function reconcile(sale:string) {
    setBusy(true);
    setMessage("Consultando el estado en Nubefact…");
    try {
      const res = await send(path+"/"+sale+"/reconcile","{}");
      if (!res.ok) throw new Error(await errorMessage(res,"No se pudo consultar el estado. Puedes volver a consultarlo."));
      const result = await res.json();
      setMessage(result.status==="BETA_REVIEW" ? "SUNAT aún no confirma el resultado. Vuelve a consultar en unos minutos." : statusLabels[result.status] || result.status);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "No se pudo consultar el CDR.");
    } finally {
      setBusy(false);
      setRefresh(value=>value+1);
    }
  }

  return <details className="mt-6 rounded-lg border p-4" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">Solicitudes de boletas y facturas del POS</summary>
    <div className="mt-3 flex items-center justify-between gap-2">
      <span>Emisión con Nubefact</span>
      <button type="button" className="underline" disabled={busy} onClick={()=>setRefresh(v=>v+1)}>Actualizar</button>
    </div>
    {loadError ? <p role="alert">No se pudo cargar la lista.</p> : !data ? <p>Cargando solicitudes…</p> :
      data.documents.length===0 ? <p>No hay solicitudes en esta página.</p> :
      <ul className="divide-y">{data.documents.map(row=><li key={row.sale} className="py-3">
        <strong>{row.kind==="FACTURA"?"Factura":"Boleta"} · {money.format(row.total)}</strong>
        <p>{row.customer || "Venta sin identificación"} · {date.format(new Date(row.created))}</p>
        <p className="break-all text-xs">Venta {row.sale}</p>
        <p>{statusLabels[row.status] || row.status}</p>
        {row.number!==null ? <p>Serie {row.series}-{String(row.number).padStart(8,"0")}</p> :
          row.status==="PENDING" && <button type="button" disabled={busy} className="text-primary underline" onClick={()=>assign(row.sale)}>Asignar número</button>}
        {row.number!==null && row.status==="PENDING" && <button type="button" disabled={busy} className="text-primary underline" onClick={()=>emit(row.sale)}>Enviar a Nubefact</button>}
        {row.status==="BETA_REVIEW" && row.submission && <button type="button" disabled={busy} className="text-primary underline" onClick={()=>reconcile(row.sale)}>Consultar estado en Nubefact</button>}
        {row.submission && <details className="my-2">
          <summary className="cursor-pointer text-primary">Detalle del envío</summary>
          <p>Intento registrado el {date.format(new Date(row.submission.attemptedAt))} (Lima).</p>
          {row.submission.cdrCode && <p>Código SUNAT: {row.submission.cdrCode}</p>}
          {row.submission.description && <p>{row.submission.description}</p>}
          {row.submission.notes.length>0 && <ul aria-label="Observaciones de SUNAT" className="list-disc pl-5">
            {row.submission.notes.map((note,index)=><li key={index}>{note}</li>)}
          </ul>}
          {row.submission.errorCode && <p>Código de error: {row.submission.errorCode}</p>}
          {row.submission.errorMessage && <p>{row.submission.errorMessage}</p>}
          {row.submission.httpStatus && <p>Nubefact respondió con HTTP {row.submission.httpStatus}.</p>}
          {row.status==="BETA_REVIEW" && <p>El resultado sigue pendiente. Usa «Consultar estado en Nubefact» para actualizar el resultado.</p>}
        </details>}
        {(row.hasXml || row.hasCdr || row.pdfUrl) && <div className="mt-2 flex flex-wrap gap-4">
          {row.pdfUrl && <a className="text-primary underline" target="_blank" rel="noopener noreferrer" href={row.pdfUrl}>Ver PDF</a>}
          {row.hasXml && <a className="text-primary underline" target="_blank" rel="noopener noreferrer"
            href={"/propietario/"+store+"/comprobantes/"+row.sale}>Ver para imprimir</a>}
          {row.hasXml && <button type="button" disabled={busy} className="text-primary underline" onClick={()=>download(row.sale,"xml")}>Descargar XML</button>}
          {row.hasCdr && <button type="button" disabled={busy} className="text-primary underline" onClick={()=>download(row.sale,"cdr")}>Descargar CDR (.zip)</button>}
        </div>}
      </li>)}</ul>}
    <p role="status">{message}</p>
    <nav aria-label="Páginas de solicitudes" className="mt-3 flex gap-3">
      {page>0 && <button type="button" disabled={busy} onClick={()=>setPage(v=>v-1)}>Anterior</button>}
      {data?.more && <button type="button" disabled={busy} onClick={()=>setPage(v=>v+1)}>Siguiente</button>}
    </nav>
  </details>;
}
