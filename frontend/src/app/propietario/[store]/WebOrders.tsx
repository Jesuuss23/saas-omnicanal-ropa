"use client";

import {useEffect,useState} from "react";

const labels={PENDING_PAYMENT:"Pendiente de pago",CANCELLED:"Cancelado",EXPIRED:"Vencido"};
type Order={id:string;name:string;phone:string;total:number;status:keyof typeof labels;createdAt:string;method?:string;address?:string;pickupHours?:string;freeDelivery?:boolean;fee?:number|null;latitude?:number|null;longitude?:number|null};
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

function DeliveryDirections({order}:{order:Order}) {
  if (order.method!=="DELIVERY") return null;
  const {latitude,longitude}=order;
  const hasPoint=typeof latitude==="number" && Math.abs(latitude)<=90 &&
    typeof longitude==="number" && Math.abs(longitude)<=180;
  const destination=hasPoint ? latitude+","+longitude : order.address?.trim();
  if (!destination) return null;
  const params=new URLSearchParams({api:"1",destination});
  return <p>
    {hasPoint && <span>Destino {destination} · </span>}
    <a href={"https://www.google.com/maps/dir/?"+params} target="_blank" rel="noopener noreferrer"
      className="text-teal-800 underline">{hasPoint?"Ver ruta al destino":"Buscar ruta por dirección"} en Google Maps</a>
  </p>;
}

function ShareTracking({store,order,path}:{store:string;order:Order;path:string}) {
  const [open,setOpen]=useState(false);
  const [notice,setNotice]=useState("");
  const {data,failed,refresh}=useOrderData<{token:string}>(open ? path+"/"+order.id+"/tracking" : null);
  const link=open && data ? new URL("/tienda/"+store+"/pedido/"+order.id+"#"+data.token,window.location.origin).href : "";
  const phone=order.phone.replace(/^\+/,"");
  const destination=/^9\d{8}$/.test(order.phone) ? "51"+phone :
    (/^\+[1-9]\d{6,14}$/.test(order.phone) || /^519\d{8}$/.test(order.phone) ? phone : "");
  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setNotice("Enlace copiado.");
    } catch {setNotice("Selecciona y copia el enlace del campo.");}
  }
  return <div>
    <button type="button" className="text-teal-800 underline" aria-expanded={open}
      onClick={()=>{setOpen(!open);setNotice("");}}> {open?"Ocultar enlace":"Compartir seguimiento"} </button>
    {open && (failed ? <p>No se pudo obtener el enlace. <button className="underline" onClick={refresh}>Reintentar</button></p> :
      !link ? <p>Cargando enlace…</p> : <div className="my-2 grid max-w-xl gap-2">
        <label>Enlace privado del pedido<input readOnly value={link} onFocus={e=>e.target.select()} className="block w-full rounded border p-2"/></label>
        <div className="flex flex-wrap gap-4">
          <button type="button" onClick={copy} className="underline">Copiar enlace</button>
          {destination && <a target="_blank" rel="noopener noreferrer" className="text-teal-800 underline"
            href={"https://wa.me/"+destination+"?"+new URLSearchParams({text:"Puedes consultar tu pedido aquí: "+link})}>
            Preparar WhatsApp para +{destination}</a>}
        </div>
        <p role="status">{notice}</p>
      </div>)}
  </div>;
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
          {o.fee!=null && <p>Envío {money.format(o.fee)} · Total {money.format(o.total+o.fee)}</p>}
          {o.freeDelivery && <p>Delivery gratis · S/ 0.00</p>}
          {o.method && <p>{o.method==="PICKUP"?"Recojo en tienda":"Delivery"} · {o.address}{o.pickupHours && " · "+o.pickupHours}</p>}
          <DeliveryDirections order={o}/>
          <p className="break-all text-sm">Pedido {o.id}</p>
          <button className="text-teal-800 underline" aria-expanded={expanded===o.id}
            onClick={()=>setExpanded(expanded===o.id?"":o.id)}>{expanded===o.id?"Ocultar prendas":"Ver prendas"}</button>
          <ShareTracking store={store} order={o} path={path}/>
          {expanded===o.id && <Items url={path+"/"+o.id+"/items"}/>}
        </li>)}</ul>}
      <nav aria-label="Páginas de pedidos" className="mt-3 flex gap-4 underline">
        {page>0 && <button onClick={()=>{setPage(page-1);setExpanded("");}}>Anterior</button>}
        {data?.more && <button onClick={()=>{setPage(page+1);setExpanded("");}}>Siguiente</button>}
      </nav>
    </>}
  </details>;
}
