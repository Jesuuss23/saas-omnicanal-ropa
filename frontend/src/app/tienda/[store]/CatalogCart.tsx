"use client";
import {apiFetch} from "@/lib/api";

import ShareCatalog from "./ShareCatalog";
import GuestContact from "./GuestContact";
import {useEffect,useMemo,useRef,useState,type CSSProperties,type ReactNode} from "react";
import {Eye, ImageOff, Minus, Plus, ShoppingBag, Trash2} from "lucide-react";
import {discountPercent, LOW_STOCK} from "@/lib/pricing";
import ProductDetail from "./ProductDetail";

export type Variant = {id:string; productId:string; name:string; category:string|null; size:string; color:string|null; price:number; available:number; coverImage:string|null;
  subcategory?:string|null; compareAtPrice?:number|null; backImage?:string|null};
type Availability = {id:string; price:number; available:number};
type StockIssue = {message:string; available:number};
type Line = Pick<Variant,"id"|"name"|"size"|"color"|"price"> & {quantity:number; available?:number};
const currency = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

function isLine(value:unknown):value is Line {
  if (!value || typeof value !== "object") return false;
  const row=value as Partial<Line>;
  return typeof row.id==="string" && typeof row.name==="string" && typeof row.size==="string" &&
    (row.color===null || typeof row.color==="string") && typeof row.price==="number" &&
    Number.isFinite(row.price) && row.price>=0 && typeof row.quantity==="number" &&
    Number.isSafeInteger(row.quantity) && row.quantity>0;
}

export default function CatalogCart({store,variants,query,pager,theme}:{store:string;variants:Variant[];query:string;pager?:ReactNode;theme?:CSSProperties}) {
  const storageKey="store-cart:v1:"+store.toLowerCase();
  const [lines,setLines]=useState<Line[]>([]);
  const [ready,setReady]=useState(false);
  const [storageError,setStorageError]=useState(false);
  const [notice,setNotice]=useState("");
  const [checking,setChecking]=useState(false);
  const [issues,setIssues]=useState<Record<string,StockIssue>>({});
  const [verified,setVerified]=useState<Record<string,Availability>>({});
  const reservationKey=storageKey+":reservation";
  const [hold,setHold]=useState<{key:string;expiresAt:string|null}|null>(null);
  const [reserving,setReserving]=useState(false);
  const locked=Boolean(hold) || reserving;
  const current=useRef<Line[]>([]);
  const products=useMemo(()=>groupByProduct(variants),[variants]);
  const [picked,setPicked]=useState<Record<string,string>>({});
  const [detail,setDetail]=useState<{product:Product; variant:string}|null>(null);

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
      const response=await apiFetch("/api/public/stores/"+store+"/reservation",{
        method:"POST",headers:{"Content-Type":"application/json"},
        body:JSON.stringify({key:pending.key,items:lines.map(({id,quantity,price})=>({id,quantity,price}))})
      });
      if ([400,404,409,410].includes(response.status)) {
        sessionStorage.removeItem(reservationKey);
        setHold(null);
        if (response.status===409) {
          await verify(true);
          return;
        }
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
      const response=await apiFetch("/api/public/stores/"+store+"/reservation",{
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

  function canIncrease(id:string,quantity:number,knownStock?:number) {
    const limit=verified[id]?.available ?? variants.find(item=>item.id===id)?.available ?? knownStock;
    if (typeof limit==="number" && quantity>=limit) {
      setNotice("Has alcanzado el stock disponible para esta variante. Puedes volver a verificarlo.");
      return false;
    }
    return true;
  }

  function add(item:Variant) {
    const cart=current.current;
    const existing=cart.find(line=>line.id===item.id);
    if (!canIncrease(item.id,existing?.quantity ?? 0,item.available)) return;
    const next={...item,price:verified[item.id]?.price ?? item.price,available:verified[item.id]?.available ?? item.available,quantity:(existing?.quantity ?? 0)+1};
    save(existing ? cart.map(line=>line.id===item.id ? next : line) : [...cart,next],
      item.name+" agregado al carrito.");
  }

  function changeQuantity(id:string,delta:number) {
    const selected=current.current.find(line=>line.id===id);
    if (!selected || (delta>0 && !canIncrease(id,selected.quantity,selected.available))) return;
    save(current.current.map(line=>line.id===id ? {...line,quantity:line.quantity+delta} : line)
      .filter(line=>line.quantity>0),"Carrito actualizado.");
  }

  async function verify(afterConflict=false) {
    const prefix=afterConflict ? "La reserva no se confirmó. " : "";
    const snapshot=current.current;
    setChecking(true);
    setIssues({});
    setNotice(afterConflict ? prefix+"Verificando precios y stock…" : "");
    try {
      const available=new Map<string,Availability>();
      for (let i=0;i<snapshot.length;i+=24) {
        const query=new URLSearchParams();
        for (const line of snapshot.slice(i,i+24)) query.append("ids",line.id);
        const response=await apiFetch("/api/public/stores/"+store+"/availability?"+query,{cache:"no-store"});
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
        if (row && row.price!==line.price) notes.push("Precio por unidad actualizado de "+currency.format(line.price)+" a "+currency.format(row.price)+".");
        if (notes.length) warnings[line.id]={message:notes.join(" "),available:row?.available ?? 0};
        return {...line,price:row?.price ?? line.price,available:row?.available ?? 0};
      });
      const message=Object.keys(warnings).length ? "Revisa las prendas marcadas." : "Precios y cantidades verificados.";
      save(next,prefix+message);
      setVerified(previous=>{
        const updated={...previous};
        for (const line of next) updated[line.id]={id:line.id,price:line.price,available:line.available};
        return updated;
      });
      setIssues(warnings);
    } catch {
      if (current.current===snapshot) setNotice(prefix+"No se pudo verificar el carrito. Vuelve a intentarlo.");
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
  const units=lines.reduce((sum,line)=>sum+line.quantity,0);
  const stockOf=(item:Variant)=>verified[item.id]?.available ?? item.available;
  const priceOf=(item:Variant)=>verified[item.id]?.price ?? item.price;

  function choose(product:Product,size:string,color:string|null) {
    const match=product.variants.find(v=>v.size===size && v.color===color)
      ?? product.variants.find(v=>v.size===size) ?? product.variants.find(v=>v.color===color);
    if (match) setPicked(previous=>({...previous,[product.id]:match.id}));
  }

  return <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
    <section aria-label="Prendas">
      {products.length ? <ul className="grid grid-cols-2 gap-x-4 gap-y-8 md:grid-cols-3">
        {products.map(product=>{
          const item=product.variants.find(v=>v.id===picked[product.id]) ?? product.variants[0];
          const sizes=[...new Set(product.variants.map(v=>v.size))];
          const colors=[...new Set(product.variants.filter(v=>v.size===item.size).map(v=>v.color).filter((c):c is string=>Boolean(c)))];
          const stock=stockOf(item);
          const prices=product.variants.map(priceOf);
          const varies=Math.min(...prices)!==Math.max(...prices);
          const discount=discountPercent(priceOf(item),item.compareAtPrice);
          const best=Math.max(0,...product.variants.map(v=>discountPercent(priceOf(v),v.compareAtPrice) ?? 0));
          const open=()=>setDetail({product,variant:item.id});
          const photo=(id:string)=>"/api/public/stores/"+store+"/images/"+id;
          return <li key={product.id} className="group flex flex-col">
            <div className="relative aspect-[4/5] overflow-hidden rounded-2xl bg-muted shadow-[var(--shadow-card)]">
              <button type="button" onClick={open} aria-label={"Ver detalle de "+product.name} className="block size-full">
                {item.coverImage
                  ? <>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={photo(item.coverImage)} alt={product.name} loading="lazy"
                      className={"size-full object-cover transition duration-500 group-hover:scale-[1.04]"+(item.backImage ? " group-hover:opacity-0" : "")}/>
                    {item.backImage && <>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={photo(item.backImage)} alt="" loading="lazy" aria-hidden
                        className="absolute inset-0 size-full object-cover opacity-0 transition duration-500 group-hover:scale-[1.04] group-hover:opacity-100"/></>}
                  </>
                  : <span className="grid size-full place-items-center text-muted-foreground"><ImageOff aria-hidden className="size-8"/></span>}
                <span className="pointer-events-none absolute inset-x-3 bottom-3 inline-flex translate-y-2 items-center justify-center gap-1.5 rounded-full bg-card/95 py-2 text-xs font-medium opacity-0 shadow-sm transition group-hover:translate-y-0 group-hover:opacity-100">
                  <Eye aria-hidden className="size-3.5"/>Ver detalle</span>
              </button>
              <div className="pointer-events-none absolute top-2 left-2 flex flex-col items-start gap-1">
                {best>0 && <span className="rounded-full bg-destructive px-2.5 py-1 text-xs font-semibold text-white shadow-sm">−{best}%</span>}
                {stock>0 && stock<=LOW_STOCK && <span className="rounded-full bg-card/90 px-2.5 py-1 text-xs font-medium shadow-sm">
                  {stock===1 ? "Última unidad" : "Quedan "+stock}</span>}
              </div>
            </div>
            <div className="mt-3 flex flex-1 flex-col gap-2">
              <div>
                {product.category && <p className="text-xs tracking-wide text-muted-foreground uppercase">{product.category}{item.subcategory ? " · "+item.subcategory : ""}</p>}
                <h2 className="leading-snug font-semibold"><button type="button" onClick={open} className="text-left hover:underline underline-offset-4">{product.name}</button></h2>
                <p className="flex flex-wrap items-baseline gap-x-2 font-semibold text-[var(--store)]">{currency.format(priceOf(item))}
                  {discount!=null && item.compareAtPrice!=null && <s className="text-sm font-normal text-muted-foreground">{currency.format(item.compareAtPrice)}</s>}
                  {varies && <span className="text-xs font-normal text-muted-foreground">según talla</span>}</p>
              </div>
              <fieldset>
                <legend className="sr-only">Talla de {product.name}</legend>
                <div className="flex flex-wrap gap-1.5">{sizes.map(size=><button key={size} type="button" aria-pressed={size===item.size}
                  onClick={()=>choose(product,size,item.color)}
                  className={"h-8 min-w-8 rounded-lg border px-2 text-xs font-medium "+(size===item.size ? "border-[var(--store)] bg-[var(--store)] text-[var(--store-fg)]" : "border-border bg-card hover:border-foreground/40")}>{size}</button>)}</div>
              </fieldset>
              {colors.length>0 && <fieldset>
                <legend className="sr-only">Color de {product.name}</legend>
                <div className="flex flex-wrap gap-1.5">{colors.map(color=><button key={color} type="button" aria-pressed={color===item.color}
                  onClick={()=>choose(product,item.size,color)}
                  className={"h-7 rounded-full border px-2.5 text-xs "+(color===item.color ? "border-foreground font-medium" : "border-border text-muted-foreground hover:border-foreground/40")}>{color}</button>)}</div>
              </fieldset>}
              <p className="text-xs text-muted-foreground">Talla {item.size}{item.color && " · "+item.color} · {stock===1 ? "1 unidad disponible" : stock+" unidades disponibles"}</p>
              <div className="mt-auto grid gap-1 pt-1">
                <button type="button" disabled={!ready || locked} onClick={()=>add(item)}
                  className="inline-flex h-10 w-full items-center justify-center gap-1.5 whitespace-nowrap rounded-full bg-[var(--store)] px-4 text-sm font-medium text-[var(--store-fg)] disabled:opacity-50">
                  <Plus aria-hidden className="size-4"/>Agregar al carrito</button>
                <ShareCatalog path={"/tienda/"+store+"?variant="+item.id} title={item.name+" · Talla "+item.size+(item.color?" · "+item.color:"")} label="Compartir prenda" tone="subtle"/>
              </div>
            </div>
          </li>;
        })}
      </ul> : <div className="rounded-2xl border border-dashed border-border px-6 py-16 text-center text-muted-foreground">
        {query?"No hay prendas disponibles para esta búsqueda.":"No hay prendas disponibles en esta página."}</div>}
      {pager}
    </section>

    {detail && <ProductDetail key={detail.product.id} store={store} product={detail.product} initialVariant={detail.variant} theme={theme} open
      onOpenChange={o=>{ if (!o) setDetail(null); }} disabled={!ready || locked} onAdd={item=>add(item)}/>}

    <aside className="lg:sticky lg:top-20 lg:self-start">
      <section className="rounded-2xl border border-border bg-card p-5 shadow-[var(--shadow-card)]" aria-label="Carrito">
        <h2 className="flex items-center gap-2 text-lg font-semibold"><ShoppingBag aria-hidden className="size-5 text-[var(--store)]"/>Tu carrito
          {units>0 && <span className="ml-auto rounded-full bg-muted px-2 py-0.5 text-xs font-medium">{units} {units===1 ? "prenda" : "prendas"}</span>}</h2>
        <p role="status" className="mt-2 text-sm text-muted-foreground empty:hidden">{notice}</p>
        {storageError && <p role="alert" className="mt-2 text-sm text-destructive">El navegador no pudo guardar o recuperar el carrito de esta pestaña.</p>}
        {!ready ? <p className="mt-3 text-sm">Cargando carrito…</p> : lines.length===0 ? <p className="mt-3 text-sm text-muted-foreground">Tu carrito está vacío. Elige una talla y agrega tus prendas.</p> : <>
          <ul className="mt-3 divide-y divide-border">{lines.map(line=><li key={line.id} className="py-3">
            <div className="flex justify-between gap-3">
              <div className="min-w-0"><p className="truncate font-medium">{line.name}</p>
                <p className="text-xs text-muted-foreground">Talla {line.size}{line.color && " · "+line.color} · {currency.format(line.price)} c/u</p></div>
              <p className="shrink-0 text-sm font-medium">{currency.format(line.price*line.quantity)}</p>
            </div>
            {issues[line.id] && <p className="mt-1 text-xs text-warning-foreground"><span className="rounded bg-warning/30 px-1">{issues[line.id].message}</span></p>}
            <div className="mt-2 flex items-center gap-2">
              <button type="button" disabled={locked} aria-label={"Restar una unidad de "+line.name} onClick={()=>changeQuantity(line.id,-1)} className="grid size-7 place-items-center rounded-full border border-border disabled:opacity-50"><Minus className="size-3.5"/></button>
              <span className="w-6 text-center text-sm tabular-nums">{line.quantity}</span>
              <button type="button" disabled={locked} aria-label={"Sumar una unidad de "+line.name} onClick={()=>changeQuantity(line.id,1)} className="grid size-7 place-items-center rounded-full border border-border disabled:opacity-50"><Plus className="size-3.5"/></button>
              <button type="button" disabled={locked} className="ml-auto inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-destructive disabled:opacity-50" onClick={()=>save(current.current.filter(row=>row.id!==line.id),"Prenda retirada.")}><Trash2 className="size-3.5"/>Retirar</button>
            </div>
          </li>)}</ul>
          <p className="flex justify-between border-t border-border pt-3 font-semibold"><span>Subtotal estimado</span><span>{currency.format(subtotal)}</span></p>
          <div className="mt-3 grid gap-2">
            {hold?.expiresAt ? <><p role="status" className="rounded-lg bg-accent px-3 py-2 text-sm text-accent-foreground">Prendas reservadas hasta las {new Date(hold.expiresAt).toLocaleTimeString("es-PE",{hour:"2-digit",minute:"2-digit"})}.</p>
              <GuestContact key={hold.key} store={store} reservation={hold.key} disabled={reserving}/>
              <button type="button" disabled={reserving} onClick={releaseReservation} className="h-10 rounded-full border border-border text-sm">
                {reserving?"Liberando…":"Liberar reserva y editar carrito"}
              </button></> :
              <button type="button" disabled={checking || reserving} onClick={reserve} className="h-11 rounded-full bg-[var(--store)] text-sm font-semibold text-[var(--store-fg)] disabled:opacity-50">
                {reserving?"Confirmando…":hold?"Reintentar reserva":"Reservar por 15 minutos"}
              </button>}
            {!locked && <button type="button" disabled={checking} onClick={()=>verify()} className="h-10 rounded-full border border-border text-sm">{checking?"Verificando…":"Verificar precios y stock"}</button>}
            {hasShortage && !locked && <button type="button" disabled={checking} onClick={applyAvailableQuantities}
              className="h-10 rounded-full border border-border text-sm">Ajustar cantidades y retirar prendas agotadas</button>}
          </div>
        </>}
      </section>
    </aside>
  </div>;
}

type Product = {id:string; name:string; category:string|null; variants:Variant[]};

function groupByProduct(variants:Variant[]):Product[] {
  const byId=new Map<string,Product>();
  for (const v of variants) {
    const product=byId.get(v.productId) ?? {id:v.productId,name:v.name,category:v.category,variants:[]};
    product.variants.push(v);
    byId.set(v.productId,product);
  }
  return [...byId.values()];
}
