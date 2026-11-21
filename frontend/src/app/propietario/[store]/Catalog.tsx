"use client";

import {useEffect, useState, useRef, type FormEvent} from "react";

import EditVariant, {type InventoryRow as Row, type Send} from "./EditVariant";

type Props = {store:string; send:Send};
type Page = {rows:Row[]; hasNext:boolean};
const money = new Intl.NumberFormat("es-PE", {style:"currency", currency:"PEN"});
const field = "mt-1 block w-full rounded-lg border border-stone-300 px-3 py-2";

export default function Catalog({store, send}:Props) {
  const [copies,setCopies] = useState<Record<string,number>>({});
  const [labelBusy,setLabelBusy] = useState(false);
  const [labelMessage,setLabelMessage] = useState("");
  const totalLabels = Object.values(copies).reduce((a,b)=>a+b,0);
  const [editing,setEditing] = useState<Row|null>(null);
  const [page,setPage] = useState(0);
  const [refresh,setRefresh] = useState(0);
  const [data,setData] = useState<Page|null>(null);
  const [listError,setListError] = useState(false);
  const [keys,setKeys] = useState([0]);
  const nextKey = useRef(1);
  const [message,setMessage] = useState("");
  const [busy,setBusy] = useState(false);
  const path = "stores/" + store + "/catalog";
  useEffect(()=>{
    const controller = new AbortController();
    setData(null); setListError(false);
    async function load() {
      try {
        const res = await fetch("/api/owner/" + path + "?page=" + page, {cache:"no-store", signal:controller.signal});
        if (!res.ok) throw new Error();
        const result:Page = await res.json();
        if (!controller.signal.aborted) setData(result);
      } catch {if (!controller.signal.aborted) setListError(true);}
    }
    void load();
    return ()=>controller.abort();
  },[page,path,refresh]);

  async function downloadLabels() {
    setLabelBusy(true);
    setLabelMessage("");
    try {
      const items = Object.entries(copies).filter(([,count])=>count > 0).map(([id,count])=>({id,copies:count}));
      const res = await send(path + "/labels", JSON.stringify({items}));
      if (!res.ok) throw new Error();
      const {pdf} = await res.json();
      const bytes = Uint8Array.from(atob(pdf), char=>char.charCodeAt(0));
      const url = URL.createObjectURL(new Blob([bytes], {type:"application/pdf"}));
      const link = document.createElement("a");
      link.href = url;
      link.download = "etiquetas-prendas.pdf";
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url), 60000);
      setLabelMessage("PDF generado. Imprime en A4 a tamaño real.");
    } catch {setLabelMessage("No se pudo generar el PDF. Comprueba la sesión y la selección.");}
    finally {setLabelBusy(false);}
  }

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const variants = keys.map(key => ({
      sku:fields.get("sku"+key), size:fields.get("size"+key), color:fields.get("color"+key),
      price:fields.get("price"+key), stock:Number(fields.get("stock"+key))
    }));
    setBusy(true); setMessage("");
    try {
      const res = await send(path,JSON.stringify({name:fields.get("name"), category:fields.get("category"), variants}));
      if (!res.ok) {
        setMessage(res.status === 409 ? "Uno de los SKU ya existe en la tienda o se repite en este formulario." :
          res.status === 400 ? "Revisa los datos de la prenda y sus variantes." : "No se pudo registrar la prenda.");
        return;
      }
      form.reset(); setKeys([0]); setPage(0); setRefresh(v=>v+1); setMessage("Prenda registrada.");
    } catch {setMessage("No se pudo confirmar el registro. Consulta el catálogo antes de reintentar.");}
    finally {setBusy(false);}
  }

  return <section className="mt-8 border-t border-stone-200 pt-6">
    <h2 className="text-2xl font-semibold">Catálogo de prendas</h2>
    <form onSubmit={save} className="my-5 rounded-xl bg-stone-50 p-5">
      <fieldset disabled={busy} className="space-y-4">
        <legend className="mb-3 text-lg font-medium">Nueva prenda</legend>
        <div className="grid gap-3 sm:grid-cols-2"><label>Nombre<input className={field} name="name" required maxLength={150}/></label>
        <label>Categoría (opcional)<input className={field} name="category" maxLength={50}/></label></div>
        {keys.map((key,i)=><fieldset key={key} className="rounded-lg border border-stone-200 p-3">
          <legend className="px-1 font-medium">Variante {i+1}</legend>
          <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
            <label>SKU<input className={field} name={"sku"+key} required maxLength={50}/></label>
            <label>Talla<input className={field} name={"size"+key} required maxLength={10}/></label>
            <label>Color<input className={field} name={"color"+key} required maxLength={30}/></label>
            <label>Precio (S/)<input className={field} name={"price"+key} type="number" min="0.01" max="99999999.99" step="0.01" required/></label>
            <label>Stock inicial<input className={field} name={"stock"+key} type="number" defaultValue="0" min="0" max="2147483647" step="1" required/></label>
          </div>{keys.length>1 && <button type="button" className="mt-2 text-sm underline" onClick={()=>setKeys(keys.filter(k=>k!==key))}>Quitar variante</button>}
        </fieldset>)}
        <div className="flex flex-wrap gap-4"><button type="button" disabled={keys.length>=100} className="text-teal-800 underline" onClick={()=>setKeys([...keys,nextKey.current++])}>Añadir variante</button>
        <button className="rounded-lg bg-teal-800 px-4 py-2 text-white">{busy?"Guardando…":"Guardar prenda"}</button></div>
      </fieldset><p aria-live="polite" className="mt-2 text-sm">{message}</p>
    </form>
    <h3 className="mb-3 text-lg font-semibold">Inventario por variante</h3>
    <div className="mb-3 flex flex-wrap items-center gap-3 text-sm">
      <span>Etiquetas seleccionadas: {totalLabels}</span>
      <button disabled={!totalLabels || totalLabels>300 || Object.values(copies).filter(n=>n>0).length>100 || labelBusy} onClick={downloadLabels}
        className="rounded-lg bg-teal-800 px-3 py-2 text-white disabled:opacity-50">{labelBusy ? "Generando…" : "Descargar etiquetas PDF"}</button>
      {totalLabels>0 && <button disabled={labelBusy} className="underline" onClick={()=>{setCopies({});setLabelMessage("");}}>Limpiar selección</button>}
      {(totalLabels>300 || Object.values(copies).filter(n=>n>0).length>100) && <span>Selecciona hasta 300 etiquetas y 100 variantes por PDF.</span>}
      <span role="status">{labelMessage}</span>
    </div>
    {editing && <EditVariant key={editing.id} row={editing} path={path} send={send} close={()=>setEditing(null)}
      reload={()=>{setEditing(null); setRefresh(v=>v+1);}}/>}
    {listError ? <button className="underline" onClick={()=>setRefresh(v=>v+1)}>Volver a cargar el inventario</button> : !data ? <p>Cargando inventario…</p> : !data.rows.length ? <p>Todavía no hay variantes en esta página.</p> :
    <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr>{["Prenda","SKU","Talla","Color","Precio","Disponible","Copias QR","Acciones"].map(label=><th className="p-2" key={label}>{label}</th>)}</tr></thead>
    <tbody>{data.rows.map(row=><tr className="border-t border-stone-200" key={row.id}>{[row.name,row.sku,row.size,row.color || "—",money.format(row.price),row.available].map((value,i)=><td className="p-2" key={i}>{value}</td>)}<td className="p-2"><input type="number" min={0} max={300} disabled={labelBusy} value={copies[row.id] || ""}
      placeholder="0" aria-label={`Copias de ${row.sku}`} className="w-16 rounded border p-1"
      onChange={event=>{const n=Number(event.target.value); if(Number.isInteger(n) && n>=0 && n<=300) setCopies(old=>({...old,[row.id]:n}));}}/></td><td className="p-2"><button disabled={!!editing} onClick={()=>setEditing(row)} className="text-teal-800 underline">Editar</button></td></tr>)}</tbody></table></div>}
    {(page>0 || data?.hasNext) && <nav aria-label="Páginas del inventario" className="mt-4 flex gap-5"><button disabled={!page} onClick={()=>setPage(page-1)}>Anterior</button><span>Página {page+1}</span><button disabled={!data?.hasNext} onClick={()=>setPage(page+1)}>Siguiente</button></nav>}
  </section>;
}
