"use client";
import {apiFetch,ownerApi} from "@/lib/api";

import {useEffect, useState} from "react";
import {useParams} from "next/navigation";

function children(node:Element | undefined, name:string):Element[] {
  return node ? Array.from(node.children).filter(child=>child.localName===name) : [];
}

function value(root:Element, ...path:string[]):string {
  let node:Element | undefined = root;
  for (const name of path) node = children(node,name)[0];
  return node?.textContent?.trim() || "";
}

function readInvoice(content:string) {
  const bytes = Uint8Array.from(atob(content), char=>char.charCodeAt(0));
  const declaration = new TextDecoder().decode(bytes.subarray(0,256));
  const encoding = declaration.match(/^\s*<\?xml\b[^?]*\bencoding=["']([^"']+)["']/i)?.[1] || "utf-8";
  const xml = new TextDecoder(encoding,{fatal:true}).decode(bytes);
  if (/<!DOCTYPE/i.test(xml)) throw new Error();
  const doc = new DOMParser().parseFromString(xml,"application/xml");
  const root = doc.documentElement;
  if (doc.getElementsByTagName("parsererror").length || root.localName!=="Invoice") throw new Error();

  const number = value(root,"ID");
  const type = value(root,"InvoiceTypeCode");
  const currency = value(root,"DocumentCurrencyCode");
  const total = value(root,"LegalMonetaryTotal","PayableAmount");
  const lines = children(root,"InvoiceLine").map(line=>({
    description:value(line,"Item","Description"), quantity:value(line,"InvoicedQuantity"),
    amount:value(line,"LineExtensionAmount"),
  }));
  if (!number || !["01","03"].includes(type) || !currency || !total || !lines.length) throw new Error();

  function party(name:string) {
    return {
      name:value(root,name,"Party","PartyLegalEntity","RegistrationName") || value(root,name,"Party","PartyName","Name"),
      id:value(root,name,"Party","PartyIdentification","ID") || value(root,name,"CustomerAssignedAccountID"),
    };
  }
  return {number, type:type==="01"?"Factura":"Boleta", currency,total,lines,
    issueDate:value(root,"IssueDate"), seller:party("AccountingSupplierParty"), buyer:party("AccountingCustomerParty"),
    tax:value(root,"TaxTotal","TaxAmount"), net:value(root,"LegalMonetaryTotal","LineExtensionAmount")};
}

export default function PrintableDocument() {
  const {store,sale} = useParams<{store:string;sale:string}>();
  const [invoice,setInvoice] = useState<ReturnType<typeof readInvoice>|null>(null);
  const [error,setError] = useState("");
  const [attempt,setAttempt] = useState(0);
  useEffect(()=>{
    const controller = new AbortController();
    async function load() {
      try {
        const res = await apiFetch(ownerApi.url("stores/"+encodeURIComponent(store)+"/billing/pos-documents/"+
          encodeURIComponent(sale)+"/files/xml"),{signal:controller.signal});
        if (res.status===401 || res.status===403) throw new Error("Inicia sesión como propietario de la tienda para ver el comprobante.");
        if (res.status===404) throw new Error("Esta venta aún no tiene un XML disponible.");
        if (!res.ok) throw new Error("No se pudo descargar el comprobante.");
        const file = await res.json();
        let data:ReturnType<typeof readInvoice>;
        try { data = readInvoice(file.content); }
        catch { throw new Error("No se pudo interpretar el XML de esta venta."); }
        if (!controller.signal.aborted) setInvoice(data);
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : "No se pudo abrir el comprobante.");
      }
    }
    void load();
    return ()=>controller.abort();
  },[store,sale,attempt]);

  return <main className="mx-auto max-w-3xl p-6 text-stone-900 print:max-w-none print:p-0">
    <nav className="mb-8 flex items-center justify-between gap-3 print:hidden">
      <a className="text-teal-800 underline" href={"/propietario/"+store}>Volver al panel</a>
      {invoice && <button className="rounded-lg bg-teal-800 px-4 py-2 text-white" onClick={()=>window.print()}>Imprimir o guardar PDF</button>}
    </nav>
    {error ? <div role="alert">{error} <button className="underline print:hidden" onClick={()=>{setInvoice(null); setError(""); setAttempt(v=>v+1);}}>Reintentar</button></div> :
      !invoice ? <p role="status">Cargando comprobante…</p> : <article>
        <header className="border-b pb-4">
          <p className="text-sm font-semibold">Entorno de pruebas · Beta</p>
          <h1 className="mt-2 text-2xl font-bold">{invoice.type} {invoice.number}</h1>
          <p>{invoice.seller.name}</p>
          {invoice.seller.id && <p>RUC {invoice.seller.id}</p>}
        </header>
        <dl className="my-5 grid gap-2 text-sm">
          <div><dt className="font-semibold">Fecha de emisión</dt><dd>{invoice.issueDate || "Sin fecha en el XML"}</dd></div>
          <div><dt className="font-semibold">Cliente</dt><dd>{[invoice.buyer.name,invoice.buyer.id].filter(Boolean).join(" · ") || "Sin identificación"}</dd></div>
        </dl>
        <table className="w-full text-sm">
          <caption className="mb-2 text-left font-semibold">Detalle de la venta en {invoice.currency}</caption>
          <thead><tr className="border-b"><th className="py-2 text-left">Descripción</th><th className="text-right">Cantidad</th><th className="text-right">Valor sin impuestos</th></tr></thead>
          <tbody>{invoice.lines.map((line,index)=><tr key={index} className="break-inside-avoid border-b">
            <td className="py-2">{line.description}</td><td className="text-right">{line.quantity}</td><td className="text-right">{line.amount}</td>
          </tr>)}</tbody>
        </table>
        <div className="mt-4 break-inside-avoid space-y-1 text-right">
          {invoice.net && <p>Valor de venta: {invoice.net}</p>}
          {invoice.tax && <p>Impuestos: {invoice.tax}</p>}
          <p className="font-bold">Total: {invoice.currency} {invoice.total}</p>
        </div>
      </article>}
  </main>;
}
