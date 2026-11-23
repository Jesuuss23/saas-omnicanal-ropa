"use client";
import {apiFetch,ownerApi,type Send} from "@/lib/api";

import {useCallback, useReducer, useRef, useState, type FormEvent} from "react";
import FiscalRequests from "./FiscalRequests";
import QrCamera from "./QrCamera";

type Item = {id:string;name:string;sku:string;size:string;color:string|null;price:number;available:number};
type Line = Item & {quantity:number};
type Cart = {lines:Line[];notice:string};
type Action = {type:"paid";message:string} | {type:"add";item:Item} | {type:"minus"|"remove";id:string} | {type:"notice";message:string};
const money = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

function reduce(cart:Cart, action:Action):Cart {
  if (action.type==="paid") return {lines:[],notice:action.message};
  if (action.type==="notice") return {...cart,notice:action.message};
  if (action.type==="add") {
    const item = action.item;
    const current = cart.lines.find(line=>line.id===item.id);
    const wanted = (current?.quantity ?? 0)+1;
    const quantity = Math.min(wanted,Math.max(0,item.available));
    const lines = cart.lines.filter(line=>line.id!==item.id);
    if (quantity>0) lines.push({...item,quantity});
    return {lines, notice:quantity<wanted ? `Disponibles para ${item.sku}: ${quantity}. Carrito actualizado.` : `Agregado: ${item.sku}.`};
  }
  return {notice:"",lines:cart.lines.flatMap(line=>{
    if (line.id!==action.id) return [line];
    return action.type==="remove" || line.quantity===1 ? [] : [{...line,quantity:line.quantity-1}];
  })};
}

function storageAvailable() {
  try {sessionStorage.getItem("pos-probe"); return true;} catch {return false;}
}

export default function PointOfSale({store,send}:{store:string;send:Send}) {
  const [salesRevision,setSalesRevision] = useState(0);
  // Se monta solo en el navegador (tras validar la sesión), así que puede leer sessionStorage al iniciar.
  const [cart,dispatch] = useReducer(reduce,{lines:[],notice:storageAvailable() ? "" : "Habilita el almacenamiento de sesión para registrar cobros."});
  const [sku,setSku] = useState("");
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
  const add = useCallback(async (key:"id"|"sku",value:string)=>{
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      const query = new URLSearchParams({[key]:value});
      const res = await apiFetch(ownerApi.url("stores/"+store+"/pos/variant?"+query));
      if (!res.ok) {
        dispatch({type:"notice",message:res.status===404 ? "La variante no figura en esta tienda." :
          res.status===401 || res.status===403 ? "Vuelve a iniciar sesión en la tienda." : "No se pudo consultar la variante."});
        return;
      }
      dispatch({type:"add",item:await res.json()});
      setSku("");
    } catch {dispatch({type:"notice",message:"No se pudo conectar con el inventario."});}
    finally {pending.current=false; setBusy(false);}
  },[store]);
  const cameraError = useCallback((message:string)=>{setCamera(false);dispatch({type:"notice",message});},[]);
  const scan = useCallback((value:string)=>{
    setCamera(false);
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) {
      dispatch({type:"notice",message:"El QR no corresponde a una etiqueta de variante."});
      return;
    }
    void add("id",value);
  },[add]);
  function search(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sku.trim()) void add("sku",sku.trim());
  }
  const cents = cart.lines.reduce((sum,line)=>sum+Math.round(line.price*100)*line.quantity,0);
  const documentType = kind === "FACTURA" ? "RUC" : buyerType;
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
      const res = await send("stores/"+store+"/pos/sales",body);
      if (res.status===400 || res.status===409) {
        sessionStorage.removeItem(storageKey);
        setUnconfirmed(false);
        dispatch({type:"notice",message:res.status===409?"Venta no registrada. Vuelve a consultar el precio y el stock de las prendas.":"Verifica los datos del cobro (máximo 100 variantes y 10 000 unidades por variante)."});
        return;
      }
      if (!res.ok) throw new Error();
      const receipt = await res.json();
      sessionStorage.removeItem(storageKey);
      setKind("BOLETA"); setBuyerType(""); setBuyerNumber(""); setBuyerName("");
      setUnconfirmed(false); setAmount(""); setReference("");
      setSalesRevision(value=>value+1);
      dispatch({type:"paid",message:`Venta ${receipt.id} registrada por ${money.format(receipt.total)}. Vuelto: ${money.format(receipt.change)}.${receipt.fiscalStatus === "PENDING" ? " Comprobante pendiente de emisión." : ""}`});
    } catch {
      dispatch({type:"notice",message:"No se pudo confirmar el registro. Reintenta desde esta pestaña antes de iniciar otro cobro."});
    } finally {pending.current=false; setBusy(false);}
  }

  return <section className="mt-8 border-t border-stone-200 pt-6">
    <h2 className="text-2xl font-semibold">Punto de venta</h2>
    <form onSubmit={search} className="my-4 flex flex-wrap items-end gap-3">
      <label>SKU de la etiqueta<input className="mt-1 block rounded-lg border px-3 py-2" maxLength={50}
        value={sku} onChange={e=>setSku(e.target.value)} required/></label>
      <button disabled={busy || camera || unconfirmed} className="rounded-lg bg-teal-800 px-4 py-2 text-white">Agregar al carrito</button>
      <button type="button" disabled={busy || unconfirmed} className="text-teal-800 underline" onClick={()=>setCamera(!camera)}>
        {camera ? "Cerrar cámara" : "Escanear etiqueta QR"}</button>
    </form>
    {camera && <QrCamera onScan={scan} onError={cameraError}/>}
    <p role="status" className="text-sm">{busy?"Consultando inventario…":cart.notice}</p>
    <h3 className="my-3 font-semibold">Carrito de mostrador</h3>
    {!cart.lines.length ? <p className="text-stone-600">El carrito está vacío.</p> :
    <><ul className="divide-y divide-stone-200">{cart.lines.map(line=><li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div><strong>{line.name}</strong><p className="text-sm">{line.sku} · {line.size} · {line.color || "Sin color"} · {money.format(line.price)}</p></div>
      <div className="flex items-center gap-3"><button disabled={busy || camera || unconfirmed} aria-label={"Restar "+line.sku} onClick={()=>dispatch({type:"minus",id:line.id})}>−</button>
      <span>{line.quantity}</span><button disabled={busy || camera || unconfirmed} aria-label={"Sumar "+line.sku} onClick={()=>void add("id",line.id)}>+</button>
      <button disabled={busy || camera || unconfirmed} className="underline" onClick={()=>dispatch({type:"remove",id:line.id})}>Quitar</button></div>
    </li>)}</ul><p className="mt-3 text-right text-lg font-semibold">Total {money.format(cents/100)}</p></>}
    {unconfirmed ? <button disabled={busy} className="my-3 underline" onClick={()=>void checkout()}>Reintentar confirmación del cobro</button> :
      cart.lines.length>0 && <form className="mt-5 space-y-3" onSubmit={e=>{e.preventDefault();void checkout();}}>
        <fieldset disabled={busy || camera} className="flex flex-wrap items-end gap-3">
          <legend className="mb-2 font-semibold">Registrar pago recibido</legend>
          <label>Comprobante<select className="block rounded border p-2" value={kind} onChange={e=>{setKind(e.target.value);setBuyerNumber("");setBuyerName("");}}>
            <option value="BOLETA">Boleta</option><option value="FACTURA">Factura</option></select></label>
          {kind==="BOLETA" && <label>Documento del comprador<select value={buyerType} required={cents>70000} onChange={e=>{setBuyerType(e.target.value);setBuyerNumber("");}} className="block rounded border p-2">
            <option value="">{cents>70000 ? "Selecciona un documento" : "Sin identificación"}</option>
            <option value="DNI">DNI</option><option value="RUC">RUC</option><option value="CE">Carné de extranjería</option><option value="PASSPORT">Pasaporte</option></select></label>}
          {documentType && <><label>{documentType==="RUC" ? "RUC del comprador" : "Número de documento"}<input required value={buyerNumber}
            pattern={documentType==="RUC" ? "[0-9]{11}" : documentType==="DNI" ? "[0-9]{8}" : "[A-Za-z0-9\\-]{1,20}"}
            maxLength={documentType==="RUC"?11:documentType==="DNI"?8:20} onChange={e=>setBuyerNumber(e.target.value)} className="block rounded border p-2"/></label>
            <label>{documentType==="RUC"?"Razón social":"Nombre completo"}<input required maxLength={200} value={buyerName} onChange={e=>setBuyerName(e.target.value)} className="block rounded border p-2"/></label></>}
          <label>Medio de pago<select value={method} onChange={e=>setMethod(e.target.value)} className="block rounded border p-2">
            <option value="CASH">Efectivo</option><option value="YAPE">Yape</option><option value="PLIN">Plin</option><option value="CARD">Tarjeta física</option>
          </select></label>
          {method==="CASH" ? <label>Efectivo recibido (S/)<input required type="number" min={(cents/100).toFixed(2)} step="0.01" value={amount} onChange={e=>setAmount(e.target.value)} className="block rounded border p-2"/></label> :
          <label>Referencia de la operación confirmada<input required maxLength={100} value={reference} onChange={e=>setReference(e.target.value)} className="block rounded border p-2"/></label>}
          <button className="rounded-lg bg-teal-800 px-4 py-2 text-white">Confirmar venta</button>
        </fieldset>
        {method==="CASH" && Number(amount)*100>=cents && <p>Vuelto: {money.format((Math.round(Number(amount)*100)-cents)/100)}</p>}
      </form>}
    <FiscalRequests store={store} send={send} salesRevision={salesRevision}/>
  </section>;
}
