"use client";
import {ownerApi} from "@/lib/api";

import {useState} from "react";
import {useApiData} from "@/lib/useApiData";

type Item={id:string;name:string;sku:string;size:string;color:string|null;price:number;available:number};
type Page={rows:Item[];hasNext:boolean};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

export default function CatalogReplyPicker({store,disabled,insert}:{
  store:string;disabled:boolean;insert:(line:string)=>void;
}) {
  const [open,setOpen]=useState(false);
  const [input,setInput]=useState("");
  const [query,setQuery]=useState("");
  const [page,setPage]=useState(0);
  const [refresh,setRefresh]=useState(0);
  const params=new URLSearchParams({availableOnly:"true",query,page:String(page)});
  const {data,error}=useApiData<Page>(open?ownerApi.url("stores/"+store+"/catalog?"+params):null,refresh);
  const failed=error!==null;

  return <details className="my-2 rounded border p-3" onToggle={e=>{e.stopPropagation();setOpen(e.currentTarget.open);}}>
    <summary className="cursor-pointer">Consultar prendas con stock</summary>
    <form className="my-2 flex flex-wrap items-end gap-2" onSubmit={e=>{
      e.preventDefault();setQuery(input.trim());setPage(0);setRefresh(n=>n+1);
    }}>
      <label>Nombre, categoría, SKU, talla o color<input value={input} onChange={e=>setInput(e.target.value)}
        maxLength={150} className="block rounded border p-1"/></label>
      <button className="rounded border px-2 py-1">Buscar</button>
    </form>
    {failed ? <p role="alert">No se pudo consultar el stock. Vuelve a buscar.</p> : !data ? <p>Cargando prendas…</p> :
      data.rows.length===0 ? <p>No hay variantes disponibles para esta búsqueda.</p> :
      <ul className="divide-y">{data.rows.map(item=>{
        const line=item.name+" · Talla "+item.size+(item.color?" · "+item.color:"")+" · "+money.format(item.price);
        return <li key={item.id} className="py-2"><p>{line}</p>
          <p className="text-sm">{item.sku} · Disponibles: {item.available}</p>
          <div className="flex flex-wrap gap-3">
            <button type="button" disabled={disabled} className="text-teal-800 underline"
              onClick={()=>insert(line)}>Agregar al mensaje</button>
            <button type="button" disabled={disabled} className="text-teal-800 underline"
              onClick={()=>{
                const url=new URL("/tienda/"+encodeURIComponent(store),window.location.origin);
                url.searchParams.set("variant",item.id);
                insert(line+"\n"+url.href);
              }}>Agregar con enlace a esta variante</button>
          </div>
        </li>;
      })}</ul>}
    <div className="flex gap-3">
      {page>0 && <button type="button" onClick={()=>setPage(n=>n-1)}>Anterior</button>}
      {data?.hasNext && <button type="button" onClick={()=>setPage(n=>n+1)}>Siguiente</button>}
    </div>
  </details>;
}
