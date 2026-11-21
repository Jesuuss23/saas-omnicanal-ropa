"use client";

import {useEffect,useState} from "react";

const labels={PENDING_PAYMENT:"Pendiente de pago",CANCELLED:"Cancelado",EXPIRED:"Vencido"};
type Order={id:string;name:string;phone:string;total:number;status:keyof typeof labels;createdAt:string};
type Page={orders:Order[];more:boolean};
type Item={variant:string;name:string;size:string;color:string|null;quantity:number;unitPrice:number};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const date=new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

function useOrderData<T>(url:string|null) {
  const [data,setData]=useState<T|null>(null);
  const [failed,setFailed]=useState(false);
  const [version,setVersion]=useState(0);
  useEffect(()=>{
    setData(null);
    setFailed(false);
    if (!url) return;
    const controller=new AbortController();
    fetch(url,{signal:controller.signal,cache:"no-store"}).then(async response=>{
      if (!response.ok) throw new Error();
      const result:T=await response.json();
      if (!controller.signal.aborted) setData(result);
    }).catch(()=>{if (!controller.signal.aborted) setFailed(true);});
    return ()=>controller.abort();
  },[url,version]);
  return {data,failed,refresh:()=>setVersion(v=>v+1)};
}

function Items({url}:{url:string}) {
  const {data,failed,refresh}=useOrderData<Item[]>(url);
  if (failed) return <p>No se pudieron cargar las prendas. <button className="underline" onClick={refresh}>Reintentar</button></p>;
  if (!data) return <p>Cargando prendas…</p>;
  return <ul className="mt-2 space-y-1 border-l pl-3">{data.map(i=><li key={i.variant}>
    {i.name} · Talla {i.size}{i.color && " · "+i.color} · {i.quantity} × {money.format(i.unitPrice)}
  </li>)}</ul>;
}

export default function WebOrders({store}:{store:string}) {
  const [open,setOpen]=useState(false);
  const [status,setStatus]=useState("");
  const [page,setPage]=useState(0);
  const [expanded,setExpanded]=useState("");
  const path="/api/owner/stores/"+store+"/web-orders";
  const params=new URLSearchParams({page:String(page)});
  if (status) params.set("status",status);
  const {data,failed,refresh}=useOrderData<Page>(open ? path+"?"+params : null);

  return <details className="my-4 rounded-xl border p-4" onToggle={e=>setOpen(e.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">Pedidos de la tienda en línea</summary>
    {open && <>
      <div className="my-3 flex flex-wrap gap-4"><label>Estado <select className="rounded border p-1" value={status}
        onChange={e=>{setStatus(e.target.value);setPage(0);setExpanded("");}}>
        <option value="">Todos</option>
        {Object.entries(labels).map(([k,v])=><option key={k} value={k}>{v}</option>)}
      </select></label><button className="underline" onClick={refresh}>Actualizar</button></div>
      {failed ? <p role="alert">No se pudieron cargar los pedidos.</p> : !data ? <p>Cargando pedidos…</p> :
        data.orders.length===0 ? <p>No hay pedidos en esta página.</p> :
        <ul className="divide-y">{data.orders.map(o=><li key={o.id} className="space-y-1 py-3">
          <p><strong>{o.name}</strong> · {o.phone}</p>
          <p>{labels[o.status]} · {money.format(o.total)} · {date.format(new Date(o.createdAt))}</p>
          <p className="break-all text-sm">Pedido {o.id}</p>
          <button className="text-teal-800 underline" aria-expanded={expanded===o.id}
            onClick={()=>setExpanded(expanded===o.id?"":o.id)}>{expanded===o.id?"Ocultar prendas":"Ver prendas"}</button>
          {expanded===o.id && <Items url={path+"/"+o.id+"/items"}/>}
        </li>)}</ul>}
      <nav aria-label="Páginas de pedidos" className="mt-3 flex gap-4 underline">
        {page>0 && <button onClick={()=>{setPage(page-1);setExpanded("");}}>Anterior</button>}
        {data?.more && <button onClick={()=>{setPage(page+1);setExpanded("");}}>Siguiente</button>}
      </nav>
    </>}
  </details>;
}
