"use client";

import {useCallback, useReducer, useRef, useState, type FormEvent} from "react";
import QrCamera from "./QrCamera";

type Item = {id:string;name:string;sku:string;size:string;color:string|null;price:number;available:number};
type Line = Item & {quantity:number};
type Cart = {lines:Line[];notice:string};
type Action = {type:"add";item:Item} | {type:"minus"|"remove";id:string} | {type:"notice";message:string};
const money = new Intl.NumberFormat("es-PE",{style:"currency",currency:"PEN"});

function reduce(cart:Cart, action:Action):Cart {
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

export default function PointOfSale({store}:{store:string}) {
  const [cart,dispatch] = useReducer(reduce,{lines:[],notice:""});
  const [sku,setSku] = useState("");
  const [camera,setCamera] = useState(false);
  const [busy,setBusy] = useState(false);
  const pending = useRef(false);
  const add = useCallback(async (key:"id"|"sku",value:string)=>{
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    try {
      const query = new URLSearchParams({[key]:value});
      const res = await fetch("/api/owner/stores/"+store+"/pos/variant?"+query,{cache:"no-store"});
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
  return <section className="mt-8 border-t border-stone-200 pt-6">
    <h2 className="text-2xl font-semibold">Punto de venta</h2>
    <form onSubmit={search} className="my-4 flex flex-wrap items-end gap-3">
      <label>SKU de la etiqueta<input className="mt-1 block rounded-lg border px-3 py-2" maxLength={50}
        value={sku} onChange={e=>setSku(e.target.value)} required/></label>
      <button disabled={busy || camera} className="rounded-lg bg-teal-800 px-4 py-2 text-white">Agregar al carrito</button>
      <button type="button" disabled={busy} className="text-teal-800 underline" onClick={()=>setCamera(!camera)}>
        {camera ? "Cerrar cámara" : "Escanear etiqueta QR"}</button>
    </form>
    {camera && <QrCamera onScan={scan} onError={cameraError}/>}
    <p role="status" className="text-sm">{busy?"Consultando inventario…":cart.notice}</p>
    <h3 className="my-3 font-semibold">Carrito de mostrador</h3>
    {!cart.lines.length ? <p className="text-stone-600">El carrito está vacío.</p> :
    <><ul className="divide-y divide-stone-200">{cart.lines.map(line=><li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div><strong>{line.name}</strong><p className="text-sm">{line.sku} · {line.size} · {line.color || "Sin color"} · {money.format(line.price)}</p></div>
      <div className="flex items-center gap-3"><button disabled={busy || camera} aria-label={"Restar "+line.sku} onClick={()=>dispatch({type:"minus",id:line.id})}>−</button>
      <span>{line.quantity}</span><button disabled={busy || camera} aria-label={"Sumar "+line.sku} onClick={()=>void add("id",line.id)}>+</button>
      <button disabled={busy || camera} className="underline" onClick={()=>dispatch({type:"remove",id:line.id})}>Quitar</button></div>
    </li>)}</ul><p className="mt-3 text-right text-lg font-semibold">Total {money.format(cents/100)}</p></>}
  </section>;
}
