"use client";
import {apiFetch, errorMessage, ownerApi, type Send} from "@/lib/api";

import {useCallback, useEffect, useReducer, useRef, useState, type FormEvent} from "react";
import {Banknote, CreditCard, ImageOff, Minus, PackageSearch, Plus, Receipt, ScanLine, Search, ShoppingBag, Smartphone, Trash2, X} from "lucide-react";
import {Alert} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from "@/components/ui/card";
import {EmptyState} from "@/components/ui/empty-state";
import {Input, NativeSelect} from "@/components/ui/input";
import {Field} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {cn, money} from "@/lib/utils";
import {useApiData} from "@/lib/useApiData";
import FiscalRequests from "./FiscalRequests";
import {photoUrl} from "./ProductPhotos";
import QrCamera from "./QrCamera";

type Option = {id:string; sku:string; size:string; color:string|null; price:number; available:number};
type Product = {id:string; name:string; category:string|null; coverImage:string|null; variants:Option[]};
type Item = Option & {name:string};
type Line = Item & {quantity:number};
type Notice = {tone:"info"|"error"|"success"; text:string} | null;
type Cart = {lines:Line[]; notice:Notice};
type Action = {type:"paid"; message:string} | {type:"add"; item:Item} | {type:"minus"|"remove"; id:string} | {type:"notice"; notice:Notice} | {type:"clear"};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const METHODS = [
  {value:"CASH", label:"Efectivo", icon:Banknote},
  {value:"YAPE", label:"Yape", icon:Smartphone},
  {value:"PLIN", label:"Plin", icon:Smartphone},
  {value:"CARD", label:"Tarjeta", icon:CreditCard},
];

function reduce(cart:Cart, action:Action):Cart {
  if (action.type==="paid") return {lines:[], notice:{tone:"success", text:action.message}};
  if (action.type==="clear") return {lines:[], notice:null};
  if (action.type==="notice") return {...cart, notice:action.notice};
  if (action.type==="add") {
    const item = action.item;
    const current = cart.lines.find(line=>line.id===item.id);
    const wanted = (current?.quantity ?? 0)+1;
    const quantity = Math.min(wanted, Math.max(0,item.available));
    const lines = current ? cart.lines.map(line=>line.id===item.id ? {...line, quantity} : line) : quantity>0 ? [...cart.lines,{...item,quantity}] : cart.lines;
    return {lines, notice:quantity<wanted
      ? {tone:"error", text:quantity ? `Solo hay ${quantity} de ${item.sku}.` : `${item.name} ${item.size} está agotado.`}
      : {tone:"info", text:`Agregado: ${item.name} · ${item.size}${item.color ? " · "+item.color : ""}`}};
  }
  return {notice:null, lines:cart.lines.flatMap(line=>{
    if (line.id!==action.id) return [line];
    return action.type==="remove" || line.quantity===1 ? [] : [{...line,quantity:line.quantity-1}];
  })};
}

function storageAvailable() {
  try {sessionStorage.getItem("pos-probe"); return true;} catch {return false;}
}

/** Caja de la tienda física: buscar o escanear la prenda, elegir talla y color, cobrar y emitir comprobante. */
export default function PointOfSale({store,send}:{store:string;send:Send}) {
  const [salesRevision,setSalesRevision] = useState(0);
  // Se monta solo en el navegador (tras validar la sesión), así que puede leer sessionStorage al iniciar.
  const [cart,dispatch] = useReducer(reduce,{lines:[], notice:storageAvailable() ? null : {tone:"error", text:"Habilita el almacenamiento de sesión para registrar cobros."}});
  const [draft,setDraft] = useState("");
  const [query,setQuery] = useState("");
  const [picked,setPicked] = useState<Product|null>(null);
  const [camera,setCamera] = useState(false);
  const [busy,setBusy] = useState(false);
  const [kind,setKind] = useState("BOLETA");
  const [buyerType,setBuyerType] = useState("");
  const [buyerNumber,setBuyerNumber] = useState("");
  const [buyerName,setBuyerName] = useState("");
  const [method,setMethod] = useState("CASH");
  const [amount,setAmount] = useState("");
  const [reference,setReference] = useState("");
  const storageKey = "pos-checkout:" + store;
  const [unconfirmed,setUnconfirmed] = useState(()=>{
    try {return Boolean(sessionStorage.getItem(storageKey));} catch {return false;}
  });
  const pending = useRef(false);
  const searchBox = useRef<HTMLInputElement>(null);
  const base = "stores/"+store+"/pos";

  useEffect(()=>{
    const timer = window.setTimeout(()=>setQuery(draft.trim()), 250);
    return ()=>window.clearTimeout(timer);
  },[draft]);
  const results = useApiData<Product[]>(ownerApi.url(base+"/products?"+new URLSearchParams({query})), salesRevision);

  const locked = busy || unconfirmed;
  const addOption = useCallback((product:{name:string}, option:Option)=>{
    dispatch({type:"add", item:{...option, name:product.name}});
  },[]);

  /** Muestra el producto para elegir talla y color; si solo tiene una variante con stock, la agrega directo. */
  const choose = useCallback((product:Product, scannedVariant?:string)=>{
    const exact = product.variants.find(v=>v.id===scannedVariant);
    const inStock = product.variants.filter(v=>v.available>0);
    if (exact) {addOption(product, exact); setPicked(null); return;}
    if (product.variants.length===1 || inStock.length===1) {addOption(product, inStock[0] ?? product.variants[0]); setPicked(null); return;}
    setPicked(product);
    dispatch({type:"notice", notice:{tone:"info", text:`Elige la talla y el color de ${product.name}.`}});
  },[addOption]);

  const lookup = useCallback(async (url:string, onFound:(body:unknown)=>void, notFound:string)=>{
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      const res = await apiFetch(ownerApi.url(url));
      if (!res.ok) {
        dispatch({type:"notice", notice:{tone:"error", text:res.status===404 ? notFound :
          res.status===401 || res.status===403 ? "Vuelve a iniciar sesión en la tienda." : await errorMessage(res, "No se pudo consultar el inventario.")}});
        return;
      }
      onFound(await res.json());
    } catch {dispatch({type:"notice", notice:{tone:"error", text:"No se pudo conectar con el inventario."}});}
    finally {pending.current=false; setBusy(false);}
  },[]);

  const cameraError = useCallback((message:string)=>{setCamera(false); dispatch({type:"notice", notice:{tone:"error", text:message}});},[]);
  const scan = useCallback((value:string)=>{
    setCamera(false);
    const code = value.trim();
    if (!UUID.test(code)) {
      // También sirve para etiquetas de otras marcas que traen el SKU impreso como QR o código de barras.
      void lookup(base+"/variant?"+new URLSearchParams({sku:code.slice(0,50)}), body=>{
        const item = body as Item;
        dispatch({type:"add", item});
      }, "Ese código no es una etiqueta de esta tienda. Busca la prenda por su nombre.");
      return;
    }
    void lookup(base+"/products/"+code, body=>choose(body as Product, code), "Ese QR no es de un producto de esta tienda.");
  },[base, choose, lookup]);

  /** Enter en el buscador: si el texto es un SKU exacto, lo agrega; si hay un solo producto, lo abre. */
  function submitSearch(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const text = draft.trim();
    if (!text) return;
    const list = query===text ? results.data ?? [] : [];
    for (const product of list) {
      const option = product.variants.find(v=>v.sku.toUpperCase()===text.toUpperCase());
      if (option) {addOption(product, option); setDraft(""); return;}
    }
    if (list.length===1) {choose(list[0]); return;}
    void lookup(base+"/variant?"+new URLSearchParams({sku:text.slice(0,50)}), body=>{
      dispatch({type:"add", item:body as Item});
      setDraft("");
    }, list.length ? "Elige la prenda en la lista de resultados." : "No hay prendas con ese nombre o SKU.");
  }

  const cents = cart.lines.reduce((sum,line)=>sum+Math.round(line.price*100)*line.quantity,0);
  const units = cart.lines.reduce((sum,line)=>sum+line.quantity,0);
  const documentType = kind === "FACTURA" ? "RUC" : buyerType;
  const received = Math.round(Number(amount || 0)*100);

  async function checkout() {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      let body = sessionStorage.getItem(storageKey);
      if (!body) {
        if (!cart.lines.length) return;
        body = JSON.stringify({id:crypto.randomUUID(),method,reference:method==="CASH"?null:reference.trim(),
          fiscal:{kind,documentType:documentType || null,documentNumber:documentType ? buyerNumber.trim() : null,customerName:documentType ? buyerName.trim() : null},
          cashReceived:method==="CASH"?amount:null, lines:cart.lines.map(({id,quantity,price})=>({id,quantity,price}))});
        sessionStorage.setItem(storageKey,body);
      }
      setUnconfirmed(true);
      const res = await send(base+"/sales",body);
      if (res.status===400 || res.status===409) {
        sessionStorage.removeItem(storageKey);
        setUnconfirmed(false);
        dispatch({type:"notice", notice:{tone:"error", text:await errorMessage(res, res.status===409
          ? "Venta no registrada: cambió el precio o el stock de alguna prenda. Quítala y vuelve a agregarla."
          : "Verifica los datos del cobro (máximo 100 variantes y 10 000 unidades por variante).")}});
        return;
      }
      if (!res.ok) throw new Error();
      const receipt = await res.json();
      sessionStorage.removeItem(storageKey);
      setKind("BOLETA"); setBuyerType(""); setBuyerNumber(""); setBuyerName("");
      setUnconfirmed(false); setAmount(""); setReference(""); setPicked(null); setDraft("");
      setSalesRevision(value=>value+1);
      dispatch({type:"paid", message:`Venta registrada por ${money.format(receipt.total)}.${method==="CASH" ? " Vuelto: "+money.format(receipt.change)+"." : ""}${receipt.fiscalStatus === "PENDING" ? " Comprobante pendiente de emisión." : ""}`});
      searchBox.current?.focus();
    } catch {
      dispatch({type:"notice", notice:{tone:"error", text:"No se pudo confirmar el registro. Reintenta desde esta pestaña antes de iniciar otro cobro."}});
    } finally {pending.current=false; setBusy(false);}
  }

  const list = results.data;
  return <div className="grid gap-6">
    <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_400px]">
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><PackageSearch className="size-5 text-primary"/>Agregar prendas</CardTitle>
          <CardDescription>Escanea el QR de la etiqueta o busca por nombre, SKU, talla o color.</CardDescription></CardHeader>
        <CardContent className="grid gap-4">
          <form onSubmit={submitSearch} role="search" className="flex flex-wrap gap-2">
            <div className="relative min-w-56 flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden/>
              <Input ref={searchBox} aria-label="Buscar prenda o SKU" placeholder="Polo negro, JEAN-AZUL-32…" value={draft} autoFocus
                onChange={e=>setDraft(e.target.value)} maxLength={150} className="h-11 pl-9 pr-9" disabled={unconfirmed}/>
              {draft && <button type="button" aria-label="Limpiar búsqueda" onClick={()=>{setDraft(""); searchBox.current?.focus();}}
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-muted"><X className="size-4"/></button>}
            </div>
            <Button type="submit" size="lg" variant="outline" className="h-11" disabled={locked || !draft.trim()}>Agregar al carrito</Button>
            <Button type="button" size="lg" className="h-11" variant={camera ? "secondary" : "default"} disabled={locked} onClick={()=>setCamera(!camera)}>
              {camera ? <><X/>Cerrar cámara</> : <><ScanLine/>Escanear etiqueta QR</>}</Button>
          </form>
          {camera && <div className="overflow-hidden rounded-lg border"><QrCamera onScan={scan} onError={cameraError}/></div>}
          <p role="status" aria-live="polite" className={cn("min-h-5 text-sm", cart.notice?.tone==="error" ? "text-destructive" : cart.notice?.tone==="success" ? "text-success" : "text-muted-foreground")}>
            {busy ? "Consultando inventario…" : cart.notice?.text}</p>

          {picked && <section aria-label={"Variantes de "+picked.name} className="grid gap-3 rounded-lg border-2 border-primary/40 bg-primary/5 p-4">
            <div className="flex items-start justify-between gap-3">
              <ProductTitle store={store} product={picked}/>
              <Button variant="ghost" size="icon" aria-label="Cerrar selección" onClick={()=>setPicked(null)}><X/></Button>
            </div>
            <VariantGrid product={picked} disabled={locked} onPick={option=>{addOption(picked, option); setPicked(null);}}/>
          </section>}

          {results.error ? <Alert variant="destructive">{results.error.message}</Alert>
            : !list ? <div className="grid gap-2">{Array.from({length:3},(_,i)=><Skeleton key={i} className="h-24"/>)}</div>
            : !list.length ? <EmptyState icon={PackageSearch} title={query ? "Sin resultados" : "Aún no hay prendas"}
                description={query ? "Prueba con otra palabra o con el SKU impreso en la etiqueta." : "Registra prendas en Catálogo para venderlas aquí."}/>
            : <ul className="grid gap-3" aria-label="Resultados">
              {list.filter(p=>p.id!==picked?.id).map(product=><li key={product.id} className="grid gap-3 rounded-lg border p-3">
                <ProductTitle store={store} product={product}/>
                <VariantGrid product={product} disabled={locked} onPick={option=>addOption(product, option)}/>
              </li>)}
            </ul>}
        </CardContent>
      </Card>

      <Card id="pos-cart" className="scroll-mt-20 lg:sticky lg:top-4">
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2"><ShoppingBag className="size-5 text-primary"/>Venta actual</CardTitle>
          {cart.lines.length>0 && !unconfirmed && <Button variant="ghost" size="sm" disabled={busy} onClick={()=>dispatch({type:"clear"})}>Vaciar</Button>}
        </CardHeader>
        <CardContent className="grid gap-4">
          {!cart.lines.length ? <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">El carrito está vacío. Escanea o busca una prenda.</p>
            : <ul className="divide-y rounded-lg border" aria-label="Carrito de mostrador">{cart.lines.map(line=><li key={line.id} className="grid gap-2 p-3">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0"><p className="truncate font-medium">{line.name}</p>
                  <p className="text-xs text-muted-foreground">{line.size}{line.color ? " · "+line.color : ""} · <span className="font-mono">{line.sku}</span></p></div>
                <Button variant="ghost" size="icon" className="size-8" disabled={locked} aria-label={"Quitar "+line.sku} onClick={()=>dispatch({type:"remove",id:line.id})}><Trash2/></Button>
              </div>
              <div className="flex items-center justify-between">
                <div className="flex items-center rounded-lg border">
                  <Button variant="ghost" size="icon" className="size-8" disabled={locked} aria-label={"Restar "+line.sku} onClick={()=>dispatch({type:"minus",id:line.id})}><Minus/></Button>
                  <span className="w-8 text-center text-sm tabular-nums" aria-label="Cantidad">{line.quantity}</span>
                  <Button variant="ghost" size="icon" className="size-8" disabled={locked || line.quantity>=line.available} aria-label={"Sumar "+line.sku} onClick={()=>dispatch({type:"add",item:line})}><Plus/></Button>
                </div>
                <span className="text-sm tabular-nums"><span className="text-muted-foreground">{money.format(line.price)} × {line.quantity} = </span><strong>{money.format(line.price*line.quantity)}</strong></span>
              </div>
            </li>)}</ul>}

          <div className="flex items-baseline justify-between border-t pt-3">
            <span className="text-sm text-muted-foreground">{units} {units===1 ? "prenda" : "prendas"}</span>
            <span className="text-2xl font-semibold tabular-nums">Total {money.format(cents/100)}</span>
          </div>

          {unconfirmed ? <Alert variant="warning" title="Cobro sin confirmar">
              <p>No sabemos si el último cobro quedó registrado. Reintenta: no se duplicará.</p>
              <Button className="mt-2" loading={busy} onClick={()=>void checkout()}>Reintentar confirmación del cobro</Button></Alert>
            : cart.lines.length>0 && <form className="grid gap-4" onSubmit={e=>{e.preventDefault(); void checkout();}}>
              <fieldset disabled={busy} className="grid gap-4">
                <legend className="sr-only">Registrar pago recibido</legend>
                <Segmented label="Comprobante" value={kind} onChange={v=>{setKind(v); setBuyerNumber(""); setBuyerName("");}}
                  options={[{value:"BOLETA",label:"Boleta",icon:Receipt},{value:"FACTURA",label:"Factura",icon:Receipt}]}/>
                {kind==="BOLETA" && <Field label="Documento del comprador" htmlFor="pos-doc" hint={cents>70000 ? "Obligatorio en boletas de más de S/ 700." : undefined}>
                  <NativeSelect id="pos-doc" value={buyerType} required={cents>70000} onChange={e=>{setBuyerType(e.target.value); setBuyerNumber("");}}>
                    <option value="">{cents>70000 ? "Selecciona un documento" : "Sin identificación"}</option>
                    <option value="DNI">DNI</option><option value="RUC">RUC</option><option value="CE">Carné de extranjería</option><option value="PASSPORT">Pasaporte</option>
                  </NativeSelect></Field>}
                {documentType && <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1">
                  <Field label={documentType==="RUC" ? "RUC del comprador" : "Número de documento"} htmlFor="pos-docnum">
                    <Input id="pos-docnum" required value={buyerNumber} inputMode={documentType==="RUC" || documentType==="DNI" ? "numeric" : undefined}
                      pattern={documentType==="RUC" ? "[0-9]{11}" : documentType==="DNI" ? "[0-9]{8}" : "[A-Za-z0-9\\-]{1,20}"}
                      maxLength={documentType==="RUC"?11:documentType==="DNI"?8:20} onChange={e=>setBuyerNumber(e.target.value)}/></Field>
                  <Field label={documentType==="RUC"?"Razón social":"Nombre completo"} htmlFor="pos-docname">
                    <Input id="pos-docname" required maxLength={200} value={buyerName} onChange={e=>setBuyerName(e.target.value)}/></Field>
                </div>}
                <Segmented label="Medio de pago" value={method} onChange={setMethod} options={METHODS}/>
                {method==="CASH" ? <Field label="Efectivo recibido (S/)" htmlFor="pos-cash">
                    <Input id="pos-cash" required type="number" inputMode="decimal" min={(cents/100).toFixed(2)} step="0.01" value={amount} onChange={e=>setAmount(e.target.value)} className="h-11 text-lg"/>
                    <div className="flex flex-wrap gap-2">
                      {[cents/100, ...[20,50,100,200].filter(n=>n*100>cents)].slice(0,4).map(n=>
                        <Button key={n} type="button" size="sm" variant="outline" onClick={()=>setAmount(n.toFixed(2))}>{n*100===cents ? "Exacto" : money.format(n)}</Button>)}
                    </div></Field>
                  : <Field label="Referencia de la operación" htmlFor="pos-ref" hint="Número de operación del Yape, Plin o voucher de la tarjeta.">
                    <Input id="pos-ref" required maxLength={100} value={reference} onChange={e=>setReference(e.target.value)}/></Field>}
                {method==="CASH" && received>=cents && <p className="flex items-baseline justify-between rounded-lg bg-success/10 px-3 py-2 text-success">
                  <span className="text-sm">Vuelto</span><strong className="text-xl tabular-nums">{money.format((received-cents)/100)}</strong></p>}
                <Button type="submit" size="lg" loading={busy}>Confirmar venta · {money.format(cents/100)}</Button>
              </fieldset>
            </form>}
        </CardContent>
      </Card>
    </div>
    <FiscalRequests store={store} send={send} salesRevision={salesRevision}/>
    {cart.lines.length>0 && <div className="sticky bottom-0 z-10 -mx-4 flex items-center justify-between gap-3 border-t bg-card/95 px-4 py-3 backdrop-blur lg:hidden">
      <span className="text-sm"><span className="text-muted-foreground">{units} {units===1 ? "prenda" : "prendas"} · </span><strong className="text-base tabular-nums">{money.format(cents/100)}</strong></span>
      <Button size="sm" onClick={()=>document.getElementById("pos-cart")?.scrollIntoView({behavior:"smooth"})}><ShoppingBag/>Cobrar</Button>
    </div>}
  </div>;
}

function ProductTitle({store, product}:{store:string; product:Product}) {
  return <div className="flex min-w-0 items-center gap-3">
    <span className="grid size-12 shrink-0 place-items-center overflow-hidden rounded-md border bg-muted text-muted-foreground">
      {/* eslint-disable-next-line @next/next/no-img-element -- imagen servida por el backend */}
      {product.coverImage ? <img src={photoUrl(store, product.coverImage)} alt="" className="size-full object-cover"/> : <ImageOff className="size-4"/>}
    </span>
    <div className="min-w-0"><p className="truncate font-medium">{product.name}</p>
      <p className="text-xs text-muted-foreground">{product.category ?? "Sin categoría"} · {product.variants.length} {product.variants.length===1 ? "variante" : "variantes"}</p></div>
  </div>;
}

/** Una tarjeta por talla y color con su SKU, precio y stock; las agotadas quedan deshabilitadas. */
function VariantGrid({product, disabled, onPick}:{product:Product; disabled:boolean; onPick:(o:Option)=>void}) {
  return <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-4">
    {product.variants.map(v=><button key={v.id} type="button" disabled={disabled || v.available<=0} onClick={()=>onPick(v)}
      aria-label={`Agregar ${product.name} talla ${v.size}${v.color ? " "+v.color : ""} (${v.sku})`}
      className="grid gap-0.5 rounded-lg border bg-card p-2 text-left text-sm transition-colors hover:border-primary hover:bg-primary/5 disabled:cursor-not-allowed disabled:opacity-50">
      <span className="flex items-center justify-between gap-1"><strong>{v.size}</strong>
        <Badge variant={v.available<=0 ? "destructive" : v.available<=2 ? "warning" : "neutral"} className="tabular-nums">{v.available<=0 ? "Agotado" : v.available+" disp."}</Badge></span>
      <span className="truncate">{v.color ?? "Sin color"}</span>
      <span className="break-all font-mono text-[11px] leading-tight text-muted-foreground">{v.sku}</span>
      <span className="font-medium tabular-nums">{money.format(v.price)}</span>
    </button>)}
  </div>;
}

function Segmented({label, value, onChange, options}:{label:string; value:string; onChange:(v:string)=>void; options:{value:string; label:string; icon:typeof Receipt}[]}) {
  return <div className="grid gap-2" role="radiogroup" aria-label={label}>
    <span className="text-sm font-medium">{label}</span>
    <div className={cn("grid gap-2", options.length>2 ? "grid-cols-4" : "grid-cols-2")}>
      {options.map(({value:v,label:text,icon:Icon})=><button key={v} type="button" role="radio" aria-checked={value===v} onClick={()=>onChange(v)}
        className={cn("flex items-center justify-center rounded-lg border font-medium transition-colors",
          options.length>2 ? "h-14 flex-col gap-1 text-xs" : "h-11 gap-2 text-sm",
          value===v ? "border-primary bg-primary text-primary-foreground" : "bg-card hover:border-primary")}>
        <Icon className="size-4" aria-hidden/>{text}</button>)}
    </div>
  </div>;
}
