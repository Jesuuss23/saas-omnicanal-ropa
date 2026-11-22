"use client";

import ShareCatalog from "./ShareCatalog";
import GuestContact from "./GuestContact";
import {useEffect,useRef,useState} from "react";

export type Variant = {id:string; name:string; category:string|null; size:string; color:string|null; price:number};
type Availability = {id:string; price:number; available:number};
type StockIssue = {message:string; available:number};
type Line = Pick<Variant,"id"|"name"|"size"|"color"|"price"> & {quantity:number};
const currency = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

function isLine(value:unknown):value is Line {
  if (!value || typeof value !== "object") return false;
  const row=value as Partial<Line>;
  return typeof row.id==="string" && typeof row.name==="string" && typeof row.size==="string" &&
    (row.color===null || typeof row.color==="string") && typeof row.price==="number" &&
    Number.isFinite(row.price) && row.price>=0 && typeof row.quantity==="number" &&
    Number.isSafeInteger(row.quantity) && row.quantity>0;
}

export default function CatalogCart({store,variants,query}:{store:string;variants:Variant[];query:string}) {
  const storageKey="store-cart:v1:"+store.toLowerCase();
  const [lines,setLines]=useState<Line[]>([]);
  const [ready,setReady]=useState(false);
  const [storageError,setStorageError]=useState(false);
  const [notice,setNotice]=useState("");
  const [checking,setChecking]=useState(false);
  const [issues,setIssues]=useState<Record<string,StockIssue>>({});
  const reservationKey=storageKey+":reservation";
  const [hold,setHold]=useState<{key:string;expiresAt:string|null}|null>(null);
  const [reserving,setReserving]=useState(false);
  const locked=Boolean(hold) || reserving;
  const current=useRef<Line[]>([]);

  useEffect(()=>{
    try {
      const raw=sessionStorage.getItem(storageKey);
      const data:unknown=raw ? JSON.parse(raw) : [];
      current.current=Array.isArray(data) ? data.filter(isLine) : [];
      setLines(current.current);
      const saved=sessionStorage.getItem(reservationKey);
      if (saved) {
        const reservation=JSON.parse(saved);
        if (typeof reservation.key==="string" && (reservation.expiresAt===null || Date.parse(reservation.expiresAt)>Date.now()))
          setHold(reservation);
        else sessionStorage.removeItem(reservationKey);
      }
    } catch {setStorageError(true);}
    setReady(true);
  },[storageKey,reservationKey]);

  useEffect(()=>{
    if (!hold?.expiresAt) return;
    const timer=setTimeout(()=>{
      setHold(null);
      try {sessionStorage.removeItem(reservationKey);} catch {setStorageError(true);}
      setNotice("La reserva venció. Puedes revisar el carrito y reservar otra vez.");
    },Math.max(0,Date.parse(hold.expiresAt)-Date.now()));
    return ()=>clearTimeout(timer);
  },[hold,reservationKey]);

  async function reserve() {
    if (reserving || checking || hold?.expiresAt) return;
    if (lines.length>100) {setNotice("Puedes reservar hasta 100 variantes por carrito.");return;}
    setReserving(true);
    try {
      const pending=hold ?? {key:crypto.randomUUID(),expiresAt:null};
      sessionStorage.setItem(reservationKey,JSON.stringify(pending));
      setHold(pending);
      const response=await fetch("/api/public/stores/"+store+"/reservation",{
        method:"POST",headers:{"Content-Type":"application/json"},
        body:JSON.stringify({key:pending.key,items:lines.map(({id,quantity,price})=>({id,quantity,price}))})
      });
      if ([400,404,409,410].includes(response.status)) {
        sessionStorage.removeItem(reservationKey);
        setHold(null);
        setNotice(response.status===410?"La reserva venció. Puedes reservar otra vez.":"Revisa precios y stock antes de volver a reservar.");
        return;
      }
      if (!response.ok) throw new Error();
      const {expiresAt}=await response.json();
      const confirmed={...pending,expiresAt};
      setHold(confirmed);
      sessionStorage.setItem(reservationKey,JSON.stringify(confirmed));
      setNotice("Stock reservado.");
    } catch {setNotice("No se pudo confirmar la reserva. Reintenta desde esta pestaña.");}
    finally {setReserving(false);}
  }

  async function releaseReservation() {
    if (!hold?.expiresAt || reserving) return;
    setReserving(true);
    try {
      const response=await fetch("/api/public/stores/"+store+"/reservation",{
        method:"DELETE",headers:{"Content-Type":"application/json"},body:JSON.stringify({key:hold.key})
      });
      if (!response.ok) throw new Error();
      try {sessionStorage.removeItem(reservationKey);} catch {setStorageError(true);}
      setHold(null);
      setNotice("Reserva liberada. Puedes editar las prendas y cantidades.");
    } catch {
      setNotice("No se pudo confirmar la liberación. Vuelve a intentarlo.");
    } finally {setReserving(false);}
  }

  function save(next:Line[],message:string) {
    setIssues({});
    current.current=next;
    setLines(next);
    setNotice(message);
    try {
      sessionStorage.setItem(storageKey,JSON.stringify(next));
      setStorageError(false);
    } catch {setStorageError(true);}
  }

  function add(item:Variant) {
    const cart=current.current;
    const existing=cart.find(line=>line.id===item.id);
    const next={...item,quantity:(existing?.quantity ?? 0)+1};
    save(existing ? cart.map(line=>line.id===item.id ? next : line) : [...cart,next],
      item.name+" agregado al carrito.");
  }

  function changeQuantity(id:string,delta:number) {
    save(current.current.map(line=>line.id===id ? {...line,quantity:line.quantity+delta} : line)
      .filter(line=>line.quantity>0),"Carrito actualizado.");
  }

  async function verify() {
    const snapshot=current.current;
    setChecking(true);
    setIssues({});
    setNotice("");
    try {
      const available=new Map<string,Availability>();
      for (let i=0;i<snapshot.length;i+=24) {
        const query=new URLSearchParams();
        for (const line of snapshot.slice(i,i+24)) query.append("ids",line.id);
        const response=await fetch("/api/public/stores/"+store+"/availability?"+query,{cache:"no-store"});
        if (!response.ok) throw new Error();
        const rows:Availability[]=await response.json();
        for (const row of rows) available.set(row.id,row);
        if (current.current!==snapshot) return;
      }
      const warnings:Record<string,StockIssue>={};
      const next=snapshot.map(line=>{
        const row=available.get(line.id);
        const notes:string[]=[];
        if (!row || row.available===0) notes.push("Agotado o retirado del catálogo.");
        else if (line.quantity>row.available) notes.push("Solo hay "+row.available+" unidades disponibles.");
        if (row && row.price!==line.price) notes.push("Precio anterior: "+currency.format(line.price)+".");
        if (notes.length) warnings[line.id]={message:notes.join(" "),available:row?.available ?? 0};
        return row ? {...line,price:row.price} : line;
      });
      save(next,Object.keys(warnings).length ? "Revisa las prendas marcadas." : "Precios y cantidades verificados.");
      setIssues(warnings);
    } catch {
      if (current.current===snapshot) setNotice("No se pudo verificar el carrito. Vuelve a intentarlo.");
    } finally {setChecking(false);}
  }

  const hasShortage=lines.some(line=>issues[line.id] && issues[line.id].available<line.quantity);
  function applyAvailableQuantities() {
    if (locked || checking || !hasShortage) return;
    const next=current.current.map(line=>{
      const issue=issues[line.id];
      return issue && issue.available<line.quantity ? {...line,quantity:issue.available} : line;
    }).filter(line=>line.quantity>0);
    save(next,"Carrito ajustado al stock de la última verificación.");
  }

  const subtotal=lines.reduce((sum,line)=>sum+Math.round(line.price*100)*line.quantity,0)/100;
  return <>
    {variants.length ? <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {variants.map(item=><li key={item.id} className="rounded-xl border border-stone-200 p-5">
        {item.category && <p className="text-sm text-stone-600">{item.category}</p>}
        <h2 className="text-xl font-semibold">{item.name}</h2>
        <p className="my-2">Talla {item.size}{item.color && " · "+item.color}</p>
        <p className="font-semibold">{currency.format(item.price)}</p>
        <button type="button" disabled={!ready || locked} onClick={()=>add(item)} className="mt-3 rounded bg-teal-800 px-3 py-2 text-white">Agregar al carrito</button>
        <ShareCatalog path={"/tienda/"+store+"?variant="+item.id} title={item.name+" · Talla "+item.size+(item.color?" · "+item.color:"")} label="Compartir prenda"/>
      </li>)}
    </ul> : <p>{query?"No hay prendas disponibles para esta búsqueda.":"No hay prendas disponibles en esta página."}</p>}
    <p role="status" className="mt-2 text-sm">{notice}</p>
    <section className="mt-8 rounded-xl border p-5" aria-label="Carrito">
      <h2 className="text-xl font-semibold">Tu carrito</h2>
      {storageError && <p role="alert">El navegador no pudo guardar o recuperar el carrito de esta pestaña.</p>}
      {!ready ? <p>Cargando carrito…</p> : lines.length===0 ? <p>Tu carrito está vacío.</p> : <>
        <ul className="divide-y">{lines.map(line=><li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
          <div><strong>{line.name}</strong><p>Talla {line.size}{line.color && " · "+line.color}</p>
            <p>{currency.format(line.price)} por unidad</p>
            {issues[line.id] && <p className="text-sm text-amber-800">{issues[line.id].message}</p>}</div>
          <div className="flex items-center gap-3">
            <button type="button" disabled={locked} aria-label={"Restar una unidad de "+line.name} onClick={()=>changeQuantity(line.id,-1)} className="rounded border px-3 py-1">−</button>
            <span>{line.quantity}</span>
            <button type="button" disabled={locked} aria-label={"Sumar una unidad de "+line.name} onClick={()=>changeQuantity(line.id,1)} className="rounded border px-3 py-1">+</button>
            <button type="button" disabled={locked} className="underline" onClick={()=>save(current.current.filter(row=>row.id!==line.id),"Prenda retirada.")}>Retirar</button>
          </div>
        </li>)}</ul>
        <p className="mt-3 font-semibold">Subtotal estimado {currency.format(subtotal)}</p>
        <button type="button" disabled={checking || locked} onClick={verify} className="my-2 rounded border px-3 py-2">{checking?"Verificando…":"Verificar precios y stock"}</button>
        {hasShortage && !locked && <button type="button" disabled={checking} onClick={applyAvailableQuantities}
          className="my-2 rounded border px-3 py-2">Ajustar cantidades y retirar prendas agotadas</button>}
        {hold?.expiresAt ? <><p role="status">Prendas reservadas hasta las {new Date(hold.expiresAt).toLocaleTimeString("es-PE",{hour:"2-digit",minute:"2-digit"})}.</p>
          <GuestContact key={hold.key} store={store} reservation={hold.key} disabled={reserving}/>
          <button type="button" disabled={reserving} onClick={releaseReservation} className="my-2 rounded border px-3 py-2">
            {reserving?"Liberando…":"Liberar reserva y editar carrito"}
          </button></> :
          <button type="button" disabled={checking || reserving} onClick={reserve} className="ml-2 rounded bg-teal-800 px-3 py-2 text-white">
            {reserving?"Confirmando…":hold?"Reintentar reserva":"Reservar por 15 minutos"}
          </button>}
      </>}
    </section>
  </>;
}
