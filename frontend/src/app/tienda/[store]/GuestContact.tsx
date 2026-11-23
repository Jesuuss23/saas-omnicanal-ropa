"use client";
import {apiFetch} from "@/lib/api";

import {useEffect,useState,type FormEvent} from "react";

import OrderSummary, {type Order} from "./OrderSummary";

export default function GuestContact({store,reservation,disabled}:{store:string;reservation:string;disabled:boolean}) {
  const url="/api/public/stores/"+store+"/reservation/guest";
  const [name,setName]=useState("");
  const [phone,setPhone]=useState("");
  const [saved,setSaved]=useState(false);
  const [order,setOrder]=useState<Order|null>(null);
  const [ready,setReady]=useState(false);
  const [busy,setBusy]=useState(false);
  const [message,setMessage]=useState("");
  const [minimum,setMinimum]=useState<number|null>(null);
  const [subtotal,setSubtotal]=useState(0);
  const [distancePricing,setDistancePricing]=useState(false);
  const [lat,setLat]=useState("");
  const [lon,setLon]=useState("");
  const [fee,setFee]=useState<number|null>(null);
  const [pickup,setPickup]=useState<{address:string;hours:string;revision:number}|null>(null);
  const [method,setMethod]=useState("");
  const [address,setAddress]=useState("");
  const needsQuote=method==="DELIVERY" && distancePricing && (minimum===null || subtotal<minimum);
  const [retry,setRetry]=useState(0);
  function reload() {setReady(false);setMessage("");setRetry(n=>n+1);}

  useEffect(()=>{
    const controller=new AbortController();
    async function load() {
      try {
        const response=await apiFetch(url,{headers:{"X-Reservation-Key":reservation},cache:"no-store",signal:controller.signal});
        if (!response.ok) throw new Error();
        const contact=await response.json();
        if (!controller.signal.aborted) {
          setName(contact.name ?? "");
          setPhone(contact.phone ?? "");
          setSaved(Boolean(contact.name && contact.phone));
          setMinimum(contact.freeDeliveryMinimum ?? null);
          setSubtotal(contact.subtotal);
          setFee(null);
          setDistancePricing(Boolean(contact.distancePricing));
          setPickup(contact.pickup ?? null);
          setMethod(contact.pickup?"":"DELIVERY");
          setOrder(contact.order);
          setReady(true);
        }
      } catch {
        if (!controller.signal.aborted) setMessage("No se pudo consultar el contacto de la reserva.");
      }
    }
    void load();
    return ()=>controller.abort();
  },[url,reservation,retry]);

  async function save(event:FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      const response=await apiFetch(url,{method:"PUT",headers:{"Content-Type":"application/json","X-Reservation-Key":reservation},
        body:JSON.stringify({name:name.trim(),phone:phone.replace(/[\s()-]/g,"")})});
      if (!response.ok) {
        setMessage(response.status===410?"La reserva ya no está activa.":response.status===400?"Revisa el nombre y el teléfono.":"No se pudo guardar el contacto.");
        return;
      }
      const contact=await response.json();
      setName(contact.name);
      setPhone(contact.phone);
      setSaved(true);
      setMessage("Datos de invitado guardados para esta reserva.");
    } catch {setMessage("No se pudo confirmar el guardado. Puedes reintentar.");}
    finally {setBusy(false);}
  }

  function useLocation() {
    if (!navigator.geolocation) {setMessage("Ingresa las coordenadas de entrega.");return;}
    setBusy(true);setFee(null);setMessage("");
    navigator.geolocation.getCurrentPosition(position=>{
      setLat(position.coords.latitude.toFixed(6));
      setLon(position.coords.longitude.toFixed(6));
      setBusy(false);
    },()=>{setBusy(false);setMessage("Puedes ingresar las coordenadas de entrega manualmente.");},
    {enableHighAccuracy:true,timeout:10000});
  }

  async function quote() {
    setBusy(true);setMessage("");setFee(null);
    try {
      const response=await apiFetch(url+"/quote",{method:"POST",headers:{"X-Reservation-Key":reservation,"Content-Type":"application/json"},
        body:JSON.stringify({latitude:Number(lat),longitude:Number(lon)})});
      if (!response.ok) throw new Error();
      const result=await response.json();
      if (result.fee==null) {reload();return;}
      setFee(result.fee);
    } catch {setMessage("No se pudo cotizar. Revisa las coordenadas y la reserva.");}
    finally {setBusy(false);}
  }

  async function confirm() {
    setBusy(true);
    setMessage("");
    try {
      const response=await apiFetch(url,{method:"POST",headers:{"X-Reservation-Key":reservation,"Content-Type":"application/json"},
        body:JSON.stringify({method,address:method==="DELIVERY"?address.trim():null,pickupRevision:pickup?.revision,freeDeliveryMinimum:minimum,point:needsQuote?{latitude:Number(lat),longitude:Number(lon)}:null,expectedFee:needsQuote?fee:null})});
      if (response.status===409) {
        reload();
        return;
      }
      if (!response.ok) {
        setMessage(response.status===410?"La reserva venció.":"No se pudo confirmar el pedido. Puedes reintentar.");
        return;
      }
      setOrder(await response.json());
    } catch {setMessage("No se pudo confirmar el pedido. Puedes reintentar.");}
    finally {setBusy(false);}
  }

  if (order) return <section className="my-2 rounded-xl border border-border p-3 text-sm" role="status">
    <h3 className="font-semibold">Pedido pendiente de pago</h3>
    <p>Código de pedido: {order.id}</p>
    <OrderSummary order={order}/>
    {order.trackingToken && <a href={"/tienda/"+store+"/pedido/"+order.id+"#"+order.trackingToken}
      className="my-2 inline-block font-medium text-[var(--store)] underline">Abrir enlace privado del pedido</a>}
    <p>Al liberar la reserva o llegar a su vencimiento, este pedido se cierra.</p>
  </section>;

  return <form onSubmit={save} className="my-2 grid gap-3 text-sm">
    <fieldset disabled={disabled || busy || !ready} className="grid gap-2">
      <legend className="font-semibold">Continuar como invitado</legend>
      <label>Nombre <input autoComplete="name" required maxLength={150} value={name}
        onChange={e=>{setName(e.target.value);setMessage("");setSaved(false);}} className="mt-1 block h-10 w-full rounded-lg border border-input bg-background px-3 text-sm"/></label>
      <label>Teléfono de contacto <input type="tel" autoComplete="tel" required maxLength={30} value={phone}
        onChange={e=>{setPhone(e.target.value);setMessage("");setSaved(false);}} className="mt-1 block h-10 w-full rounded-lg border border-input bg-background px-3 text-sm"/></label>
      <button className="h-10 w-full rounded-full bg-[var(--store)] px-4 text-sm font-semibold text-[var(--store-fg)] disabled:opacity-50">{busy?"Guardando…":"Guardar contacto"}</button>
      {saved && <>
        <label>Forma de entrega <select value={method} onChange={e=>setMethod(e.target.value)} className="h-10 rounded-lg border border-input bg-background px-3 text-sm">
          {pickup && <option value="">Elige una opción</option>}
          <option value="DELIVERY">Delivery</option>
          {pickup && <option value="PICKUP">Recojo en tienda</option>}
        </select></label>
        {method==="PICKUP" && pickup && <p>{pickup.address} · {pickup.hours}</p>}
        {method==="DELIVERY" && <label>Dirección de entrega<input value={address} maxLength={250} autoComplete="street-address"
          onChange={e=>{setAddress(e.target.value);setFee(null);}} className="mt-1 block h-10 w-full rounded-lg border border-input bg-background px-3 text-sm"/>
          <span className="text-sm">{minimum!==null && subtotal>=minimum ? "Delivery gratis para este pedido." :
            needsQuote ? "La tarifa se calcula desde el punto de despacho hasta las coordenadas de entrega." : minimum!==null ? "Delivery gratis desde S/ "+minimum.toFixed(2)+". Tarifa de este pedido pendiente." : "Tarifa de envío pendiente."}</span></label>}
        {needsQuote && <>
          <button type="button" onClick={useLocation} className="w-fit underline">Usar mi ubicación como destino</button>
          <label>Latitud de entrega<input type="number" min="-90" max="90" step="0.000001" value={lat} onChange={e=>{setLat(e.target.value);setFee(null);}} className="mt-1 block h-10 w-full rounded-lg border border-input bg-background px-3 text-sm"/></label>
          <label>Longitud de entrega<input type="number" min="-180" max="180" step="0.000001" value={lon} onChange={e=>{setLon(e.target.value);setFee(null);}} className="mt-1 block h-10 w-full rounded-lg border border-input bg-background px-3 text-sm"/></label>
          <button type="button" className="w-fit underline" disabled={!lat || !lon} onClick={quote}>Cotizar por distancia en línea recta</button>
          {fee!==null && <p>Envío S/ {fee.toFixed(2)} · Total S/ {(subtotal+fee).toFixed(2)}</p>}
        </>}
        <button type="button" disabled={(needsQuote && fee===null) || !method || (method==="DELIVERY" && !address.trim())} onClick={confirm} className="h-10 w-full rounded-full border border-border px-4 text-sm">Confirmar pedido pendiente de pago</button></>}
    </fieldset>
    <p role="status">{message}</p>
    {!ready && message && <button type="button" onClick={reload} className="w-fit underline">Reintentar consulta</button>}
  </form>;
}
