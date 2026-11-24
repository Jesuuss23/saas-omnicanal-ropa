"use client";
import {errorMessage, type Send} from "@/lib/api";

import {getOrderAmounts} from "@/components/OrderAmounts";
import {useState} from "react";
import {ChevronDown, MapPin, Package, RefreshCw, Share2, Store, Truck} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {Input, Textarea} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Pagination} from "@/components/console/Pagination";
import {cn} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";

const labels={PENDING_PAYMENT:"Pendiente de pago",PAID:"Pagado",CANCELLED:"Cancelado",EXPIRED:"Vencido"};
const tones={PENDING_PAYMENT:"warning",PAID:"success",CANCELLED:"neutral",EXPIRED:"neutral"} as const;
type Order={id:string;name:string;phone:string;total:number;status:keyof typeof labels;createdAt:string;method?:string;address?:string;pickupHours?:string;freeDelivery?:boolean;fee?:number|null;latitude?:number|null;longitude?:number|null};
type Page={orders:Order[];more:boolean};
type Item={variant:string;name:string;size:string;color:string|null;quantity:number;unitPrice:number};
const money=new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});
const date=new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});

function useOrderData<T>(url:string|null) {
  const {data,error,reload}=useApiData<T>(url);
  return {data,failed:error!==null,refresh:reload};
}

function Items({url}:{url:string}) {
  const {data,failed,refresh}=useOrderData<Item[]>(url);
  if (failed) return <Alert variant="destructive">No se pudieron cargar las prendas. <button className="underline" onClick={refresh}>Reintentar</button></Alert>;
  if (!data) return <Skeleton className="h-16"/>;
  return <ul className="divide-y rounded-lg border text-sm">{data.map(i=><li key={i.variant} className="flex items-center justify-between gap-3 px-3 py-2">
    <span><span className="font-medium">{i.name}</span> <span className="text-muted-foreground">· Talla {i.size}{i.color && " · "+i.color}</span></span>
    <span className="whitespace-nowrap tabular-nums">{i.quantity} × {money.format(i.unitPrice)}</span>
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
  return <a href={"https://www.google.com/maps/dir/?"+params} target="_blank" rel="noopener noreferrer"
      className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"><MapPin className="size-4"/>{hasPoint?"Ver ruta al destino":"Buscar ruta por dirección"} en Google Maps</a>;
}

function ShareTracking({store,order,path}:{store:string;order:Order;path:string}) {
  const [open,setOpen]=useState(false);
  const [notice,setNotice]=useState("");
  const {data,failed,refresh}=useOrderData<{token:string}>(open ? path+"/"+order.id+"/tracking" : null);
  const link=open && data ? new URL("/tienda/"+store+"/pedido/"+order.id+"#"+data.token,window.location.origin).href : "";
  const [draft,setDraft]=useState<string|null>(null);
  const {label,total}=getOrderAmounts({merchandise:order.total,method:order.method,freeDelivery:order.freeDelivery,fee:order.fee});
  const summary = [
    `Hola, ${order.name}. Tu pedido figura como ${(labels[order.status] ?? "registrado").toLowerCase()}.`,
    `Prendas: ${money.format(order.total)}.`,
    label,
    total===null ? "" : `Total del pedido: ${money.format(total)}.`
  ].filter(Boolean).join("\n");
  const message = (draft ?? summary).trim();
  const withTracking = [message,link].filter(Boolean).join("\n\n");
  const phone=order.phone.replace(/^\+/,"");
  const destination=/^9\d{8}$/.test(order.phone) ? "51"+phone :
    (/^\+[1-9]\d{6,14}$/.test(order.phone) || /^519\d{8}$/.test(order.phone) ? phone : "");
  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setNotice("Enlace copiado.");
    } catch {setNotice("Selecciona y copia el enlace del campo.");}
  }
  return <div className="grid gap-2">
    <Button type="button" variant="outline" size="sm" className="w-fit" aria-expanded={open}
      onClick={()=>{setOpen(!open);setNotice("");}}><Share2/>{open?"Ocultar enlace":"Compartir seguimiento"}</Button>
    {open && (failed ? <Alert variant="destructive">No se pudo obtener el enlace. <button className="underline" onClick={refresh}>Reintentar</button></Alert> :
      !link ? <Skeleton className="h-24"/> : <div className="grid max-w-xl gap-3 rounded-lg border p-3">
        <Field label="Enlace privado del pedido" htmlFor={"link-"+order.id}><Input id={"link-"+order.id} readOnly value={link} onFocus={e=>e.target.select()}/></Field>
        <Field label="Mensaje para el cliente" htmlFor={"msg-"+order.id} hint="El enlace privado se incluye al final del mensaje.">
          <Textarea id={"msg-"+order.id} rows={5} value={draft ?? summary} onChange={e=>setDraft(e.target.value)}/></Field>
        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="outline" onClick={async ()=>{
            try {
              await navigator.clipboard.writeText(withTracking);
              setNotice("Mensaje con enlace copiado.");
            } catch {setNotice("Copia el texto del mensaje y el enlace desde sus campos.");}
          }}>Copiar mensaje</Button>
          <Button type="button" size="sm" variant="outline" onClick={copy}>Copiar enlace</Button>
          {destination && <Button asChild size="sm"><a target="_blank" rel="noopener noreferrer"
            href={"https://wa.me/"+destination+"?"+new URLSearchParams({text:withTracking})}>
            Enviar por WhatsApp a +{destination}</a></Button>}
        </div>
        <p role="status" className="text-sm text-muted-foreground">{notice}</p>
      </div>)}
  </div>;
}


function DeliveryQuote({store,order,send,refresh}:{store:string;order:Order;send:Send;refresh:()=>void}) {
  const [amount,setAmount]=useState("");
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState("");
  return <form className="flex flex-wrap items-end gap-2 rounded-lg border border-warning/40 bg-warning/5 p-3" onSubmit={async e=>{
    e.preventDefault();
    if (busy) return;
    setBusy(true);setMessage("");
    try {
      const res=await send("stores/"+store+"/web-orders/"+order.id+"/delivery-quote",JSON.stringify({fee:amount}),"PUT");
      if (!res.ok) throw new Error(res.status===409?"El pedido ya tiene tarifa o la reserva dejó de estar activa. Actualiza la lista.":await errorMessage(res,"No se pudo guardar la tarifa."));
      refresh();
    } catch(err) {setMessage(err instanceof Error?err.message:"No se pudo guardar la tarifa.");}
    finally {setBusy(false);}
  }}>
    <Field label="Tarifa de envío (S/)" htmlFor={"fee-"+order.id} hint="El cliente la verá antes de pagar.">
      <Input id={"fee-"+order.id} type="number" min="0" max="999999999999.99" step="0.01" required value={amount}
        disabled={busy} onChange={e=>setAmount(e.target.value)} className="w-40"/></Field>
    <Button loading={busy}>Confirmar tarifa</Button>
    {message && <p role="status" className="w-full text-sm text-destructive">{message}</p>}
  </form>;
}

const filters:[string,string][] = [["","Todos"],["PENDING_PAYMENT","Pendientes"],["PAID","Pagados"],["CANCELLED","Cancelados"],["EXPIRED","Vencidos"]];

export default function WebOrders({store,send}:{store:string;send:Send}) {
  const [status,setStatus]=useState("");
  const [page,setPage]=useState(0);
  const [expanded,setExpanded]=useState("");
  const path="/api/owner/stores/"+store+"/web-orders";
  const params=new URLSearchParams({page:String(page)});
  if (status) params.set("status",status);
  const {data,failed,refresh}=useOrderData<Page>(path+"?"+params);

  return <div className="grid gap-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div role="tablist" aria-label="Estado del pedido" className="flex flex-wrap gap-1 rounded-lg bg-muted p-1">
        {filters.map(([k,v])=><button key={k} role="tab" aria-selected={status===k} onClick={()=>{setStatus(k);setPage(0);setExpanded("");}}
          className={cn("rounded-md px-3 py-1.5 text-sm font-medium transition-colors", status===k ? "bg-card shadow-xs" : "text-muted-foreground hover:text-foreground")}>{v}</button>)}
      </div>
      <Button variant="outline" size="sm" onClick={refresh}><RefreshCw/>Actualizar</Button>
    </div>
    {failed ? <Alert variant="destructive" title="No se pudieron cargar los pedidos"><button className="underline" onClick={refresh}>Reintentar</button></Alert>
      : !data ? <div className="grid gap-3">{Array.from({length:3},(_,i)=><Skeleton key={i} className="h-28"/>)}</div>
      : data.orders.length===0 ? <EmptyState icon={Package} title={status ? "No hay pedidos con este estado" : "Aún no tienes pedidos web"}
          description="Cuando un cliente compre en tu tienda en línea, su pedido aparecerá aquí."/>
      : <ul className="grid gap-3">{data.orders.map(o=>{
        const amounts=getOrderAmounts({merchandise:o.total,method:o.method,freeDelivery:o.freeDelivery,fee:o.fee});
        const open=expanded===o.id;
        return <li key={o.id}><Card className="grid gap-3 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="grid gap-1">
              <p className="flex flex-wrap items-center gap-2"><strong>{o.name}</strong><Badge variant={tones[o.status] ?? "neutral"}>{labels[o.status] ?? o.status}</Badge></p>
              <p className="text-sm text-muted-foreground">{o.phone} · {date.format(new Date(o.createdAt))} · <span className="font-mono text-xs">#{o.id.slice(0,8)}</span></p>
            </div>
            <div className="text-right">
              <p className="text-lg font-semibold tabular-nums">{money.format(amounts.total ?? o.total)}</p>
              <p className="text-xs text-muted-foreground">Prendas {money.format(o.total)}{amounts.label ? " · "+amounts.label : ""}</p>
            </div>
          </div>
          {o.method && <p className="flex items-start gap-2 text-sm">{o.method==="PICKUP" ? <Store className="mt-0.5 size-4 text-muted-foreground"/> : <Truck className="mt-0.5 size-4 text-muted-foreground"/>}
            <span>{o.method==="PICKUP"?"Recojo en tienda":"Delivery"}{o.address && " · "+o.address}{o.pickupHours && " · "+o.pickupHours}</span></p>}
          {o.method==="DELIVERY" && o.status==="PENDING_PAYMENT" && !o.freeDelivery && o.fee==null &&
            <DeliveryQuote store={store} order={o} send={send} refresh={refresh}/>}
          <DeliveryDirections order={o}/>
          <div className="flex flex-wrap items-start gap-2">
            <Button variant="ghost" size="sm" aria-expanded={open} onClick={()=>setExpanded(open?"":o.id)}>
              <ChevronDown className={cn("transition-transform", open && "rotate-180")}/>{open?"Ocultar prendas":"Ver prendas"}</Button>
            <ShareTracking store={store} order={o} path={path}/>
          </div>
          {open && <Items url={path+"/"+o.id+"/items"}/>}
        </Card></li>;})}</ul>}
    {data && <Pagination page={page} hasNext={data.more} onPage={p=>{setPage(p);setExpanded("");}}/>}
  </div>;
}
